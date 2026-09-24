package com.clockin.hackathon

import com.clockin.hackathon.i18n.Message
import com.clockin.hackathon.i18n.tr
import com.clockin.hackathon.i18n.AppLanguage

import android.app.Application
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clockin.hackathon.moderation.*
import com.clockin.hackathon.capture.DraftCodec
import com.clockin.hackathon.capture.LocalDraft
import com.clockin.hackathon.capture.LocalEncryption
import com.clockin.hackathon.capture.PhotoPair
import com.clockin.hackathon.chain.Base58
import com.clockin.hackathon.chain.CheckInAccount
import com.clockin.hackathon.chain.ClockInAccounts
import com.clockin.hackathon.chain.ClockInAddresses
import com.clockin.hackathon.chain.ClockInInstructions
import com.clockin.hackathon.chain.ConfigAccount
import com.clockin.hackathon.chain.ProfileAccount
import com.clockin.hackathon.chain.SolanaRpc
import com.clockin.hackathon.chain.TransactionBuilder
import com.clockin.hackathon.provenance.PostManifest
import com.clockin.hackathon.backend.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import com.clockin.hackathon.wallet.WalletException
import com.clockin.hackathon.wallet.WalletFailure
import com.clockin.hackathon.wallet.WalletSession
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.TransactionInstruction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.time.Instant
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Une unité de SKR affichable : le mint de test a 9 décimales. */
const val SKR: Long = 1_000_000_000L

/**
 * État lu sur la chaîne. Source de vérité unique depuis la version 0.3 :
 * aucun solde n'est simulé côté app.
 */
data class ChainState(
    val config: ConfigAccount? = null,
    val profile: ProfileAccount? = null,
    val todayCheckIn: CheckInAccount? = null,
    val tokenBalance: Long = 0,
    val day: Long = 0,
    val loaded: Boolean = false,
) {
    /** Solde réel : celui du compte, après le decay déjà dû. */
    val balance: Long
        get() = if (profile == null || config == null) {
            0
        } else {
            profile.settledBalance(config.decayBps, config.maxDecayDays, day)
        }

    /**
     * La mise plancher du produit, tant que la Config on-chain n'est pas lue.
     *
     * La chaîne fait foi dès qu'elle répond : cette valeur n'est vue qu'avant la
     * première lecture. `program/scripts/bootstrap-devnet.ts` écrit la même.
     */
    val minStake: Long get() = config?.minStake ?: (500 * SKR)

    /** Pénalité d'un jour UTC manqué, en points de base, Config non lue comprise. */
    val decayBps: Int get() = config?.decayBps ?: 2_500
    val streak: Long get() = profile?.streak ?: 0
    val totalCheckIns: Long get() = profile?.totalCheckIns ?: 0
    val active: Boolean get() = profile?.active == true
    val hasProfile: Boolean get() = profile != null
    val faucetClaimed: Boolean get() = profile?.faucetClaimed == true
    val exitUnlockAt: Long get() = profile?.exitUnlockAt ?: 0
    val posted: Boolean get() = todayCheckIn != null

    /**
     * Ce qu'un Moment publié peut rapporter aujourd'hui : une part de la mise
     * réglée, bornée par le plafond de la Config.
     *
     * C'est une promesse de la Config, pas un historique : la chaîne ne tient
     * aucun journal des parts déjà reçues.
     */
    val dailyReward: Long
        get() = config?.let { minOf(balance / 10_000 * it.rewardRateBps, it.rewardCap) } ?: 0

    /** Le feed n'est lisible qu'après son propre check-in (§3). */
    val feedUnlocked: Boolean get() = posted

    fun canPublish(now: Long): Boolean =
        !posted && active && balance >= minStake && (exitUnlockAt == 0L || now < exitUnlockAt)
}

/**
 * Orchestration de l'app : lecture des comptes, construction et envoi des
 * transactions, brouillon de capture chiffré localement.
 *
 * Rien n'est journalisé : ni photos, ni transactions, ni signatures.
 */
class ClockInModel(application: Application) : AndroidViewModel(application) {
    private val programId = SolanaPublicKey(Base58.decode(BuildConfig.PROGRAM_ID))
    private val mint: SolanaPublicKey? =
        BuildConfig.SKR_MINT.takeIf { it.isNotBlank() }?.let { SolanaPublicKey(Base58.decode(it)) }
    private val rpc = SolanaRpc(BuildConfig.RPC_URL)
    private val file = AtomicFile(File(application.noBackupFilesDir, "moment-draft.bin"))

    private val backend by lazy { KeyServer(BuildConfig.BACKEND_URL, BuildConfig.DEBUG) }
    private val pending = PendingPostStore(application.noBackupFilesDir, ::keystoreKey)
    var hasPendingPublication by mutableStateOf(false)
        private set
    private var feedJob: kotlinx.coroutines.Job? = null
    private var sessionGeneration = 0L
    /**
     * La session keyserver survit au redémarrage de l'app : sans elle, chaque
     * lancement rouvrirait le wallet pour signer avant même d'afficher le fil.
     */
    private val sessionStore = application.getSharedPreferences("backend-session", android.content.Context.MODE_PRIVATE)
    private var authToken: String? = sessionStore.getString("token", null)
    private var authWallet: String? = sessionStore.getString("wallet", null)
    private var authExpires = sessionStore.getLong("expires", 0L)
    /** Le wallet dont la session keyserver est valide : c'est lui qui est « connecté ». */
    var signedInWallet by mutableStateOf(authWallet?.takeIf { authToken != null && Instant.now().epochSecond + 30 < authExpires })
        private set
    private fun clearBackendSession() {
        authToken = null; authWallet = null; authExpires = 0; signedInWallet = null
        sessionStore.edit().clear().apply()
    }
    private fun hasBackendSession(owner: SolanaPublicKey) =
        authToken != null && authWallet == owner.base58() && Instant.now().epochSecond + 30 < authExpires
    var remoteFeed by mutableStateOf<List<RemoteMoment>>(emptyList())
        private set
    var feedCursor by mutableStateOf<String?>(null)
        private set
    var feedLoading by mutableStateOf(false)
        private set
    var feedNeedsSignature by mutableStateOf(false)
        private set
    var feedError by mutableStateOf<String?>(null)
        private set

    private suspend fun authenticate(session: WalletSession, owner: SolanaPublicKey): String {
        val generation = sessionGeneration
        val now = Instant.now().epochSecond
        if (authWallet == owner.base58() && now + 30 < authExpires) authToken?.let { return it }
        val challenge = backend.challenge(owner.base58())
        val message = challengeMessage(challenge, backend.origin, BuildConfig.NETWORK, BuildConfig.PROGRAM_ID, owner.base58(), now)
        val signature = session.signMessage(message).getOrThrow()
        verifySignature(owner.bytes, message, signature)
        val result = backend.verify(owner.base58(), challenge.string("nonce"), signature)
        check(session.address == owner && generation == sessionGeneration)
        authWallet = owner.base58(); authExpires = result.string("expiresAt").toLong()
        authToken = result.string("token")
        signedInWallet = authWallet
        sessionStore.edit().putString("token", authToken).putString("wallet", authWallet)
            .putLong("expires", authExpires).apply()
        return authToken!!
    }

    /** Optimistic like toggle; the server count wins once it answers. */
    fun toggleLike(commitment: String) {
        val session = wallet ?: return
        val owner = session.address ?: return
        val current = remoteFeed.firstOrNull { it.commitment == commitment } ?: return
        val wanted = !current.liked
        fun update(likes: Int, liked: Boolean) {
            remoteFeed = remoteFeed.map { if (it.commitment == commitment) it.copy(likes = likes, liked = liked) else it }
        }
        update(current.likes + if (wanted) 1 else -1, wanted)
        viewModelScope.launch {
            try {
                val (likes, liked) = backend.like(commitment, wanted, authenticate(session, owner))
                update(likes, liked)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { update(current.likes, current.liked); error = messageFor(failure) }
        }
    }

    /**
     * Le fil a besoin d'une session keyserver, qui se signe dans le wallet.
     * Un chargement automatique ([interactive] faux) n'ouvre jamais le wallet :
     * il signale seulement [feedNeedsSignature], et c'est un geste de
     * l'utilisateur (bouton, tirer pour actualiser) qui lance la signature.
     */
    fun loadFeed(more: Boolean = false, interactive: Boolean = false) {
        val session = wallet ?: return
        val owner = session.address ?: return
        if (busy || feedLoading || !state.posted) return
        if (!interactive && !hasBackendSession(owner)) { feedNeedsSignature = true; return }
        val generation = sessionGeneration
        feedNeedsSignature = false
        feedError = null
        feedLoading = true
        feedJob = viewModelScope.launch {
            try {
                val day = utcDay()
                val token = authenticate(session, owner)
                val saved = withContext(Dispatchers.IO) { pending.read(owner.base58(), day) }
                if (saved != null) {
                    check(state.todayCheckIn?.commitment?.hex() == saved.commitment && state.todayCheckIn?.blobRef?.hex() == saved.blobRef)
                    backend.confirm(saved.commitment, token)
                    withContext(Dispatchers.IO) { pending.clear(owner.base58(), day) }
                    hasPendingPublication = false
                }
                val page = backend.feed(day, if (more) feedCursor else null, token)
                val items = withContext(Dispatchers.IO) { page.items.map { item ->
                    val post = SealedPost(day, item.commitment, item.blobRef, unb64(item.postKey), backend.blob(item.blobRef, token))
                    val opened = PostPacket.open(post, Base58.decode(item.wallet), BuildConfig.NETWORK, programId.bytes)
                    RemoteMoment(item.wallet, item.commitment, opened.photos, opened.caption, item.likes, item.liked)
                } }
                if (generation == sessionGeneration && wallet?.address == owner && day == utcDay()) {
                    remoteFeed = ((if (more) remoteFeed else emptyList()) + items).distinctBy { it.commitment }
                    feedCursor = page.next
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == sessionGeneration) {
                    // Une signature refusée ou interrompue ne coupe rien : le fil
                    // déjà chargé reste, et l'erreur s'affiche dans le fil, sans
                    // dialogue qui bloquerait la navigation.
                    if (failure !is WalletException) { remoteFeed = emptyList(); feedCursor = null }
                    feedError = messageFor(failure)
                }
            }
            finally { if (generation == sessionGeneration) feedLoading = false }
        }
    }

    var state by mutableStateOf(ChainState())
        private set
    var busy by mutableStateOf(false)
        private set
    private var storedError by mutableStateOf<String?>(null)
    var error: String?
        get() = storedError?.let { com.clockin.hackathon.i18n.localizeError(it) }
        private set(value) { storedError = value }
    var draft by mutableStateOf<PhotoPair?>(null)
        private set
    /**
     * La légende du Moment publié aujourd'hui.
     *
     * Elle n'entre pas dans le manifeste signé : la chaîne ne connaît que les
     * deux photos. Elle est donc écrite avec le brouillon, une fois seulement —
     * à la publication — plutôt qu'à chaque frappe, qui réécrirait les 8 Mo du
     * brouillon pour un caractère.
     */
    var caption by mutableStateOf("")
        private set
    var wallet: WalletSession? = null
    var moderation by mutableStateOf<ModerationState>(ModerationState.Empty)
        private set
    private val moderationCoordinator = ModerationCoordinator(
        viewModelScope, (application as MomentApplication).moderator::analyze,
    ) { moderation = it }

    fun retryModeration() {
        if (!busy) moderationCoordinator.replace(draft)
    }

    val walletAddress: String? get() = wallet?.address?.base58()

    init {
        loadDraft()
    }

    fun dismissError() {
        error = null
    }

    fun replaceDraft(photos: PhotoPair?) {
        if (busy) return
        if (hasPendingPublication) { error = tr(Message.ResumePendingExplanation); return }
        draft = photos
        caption = ""
        moderationCoordinator.replace(photos)
        viewModelScope.launch { saveDraft(photos, caption = "") }
    }

    fun connect() {
        val session = wallet ?: return
        if (busy || feedLoading) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                val owner = session.connect().getOrElse { error = messageFor(it); return@launch }
                // Être connecté, c'est avoir signé la session keyserver : une
                // signature refusée laisse déconnecté, pas à moitié connecté.
                try { authenticate(session, owner) }
                catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    session.forget(); error = messageFor(failure); return@launch
                }
                refreshNow(owner)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = tr(Message.CouldnTReadTheBlockchainCheckYour)
            } finally {
                busy = false
                if (state.posted) loadFeed()
            }
        }
    }

    /**
     * Resigne la session keyserver d'un wallet déjà connu (session expirée ou
     * révoquée). Tant qu'elle manque, l'app n'affiche que l'écran de connexion.
     */
    fun signIn() {
        val session = wallet ?: return
        val owner = session.address ?: return
        if (busy || feedLoading) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                authenticate(session, owner)
                refreshNow(owner)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = messageFor(failure)
            } finally {
                busy = false
                if (state.posted) loadFeed()
            }
        }
    }

    /**
     * Oublie la session wallet. L'état lu sur la chaîne repart à zéro : rien de
     * ce qui s'affiche ne doit survivre au wallet dont il provient. Le brouillon
     * local, lui, appartient à l'appareil et reste en place.
     */
    fun disconnect() {
        if (busy) return
        sessionGeneration++
        feedJob?.cancel(); feedLoading = false; hasPendingPublication = false
        clearBackendSession()
        feedNeedsSignature = false; feedError = null
        remoteFeed = emptyList(); feedCursor = null
        wallet?.forget()
        state = ChainState()
        error = null
    }

    fun refresh() {
        val owner = wallet?.address ?: return
        viewModelScope.launch {
            try {
                refreshNow(owner)
            } catch (_: Exception) {
                error = tr(Message.CouldnTReadTheBlockchainCheckYour)
            }
        }
    }

    fun claimFaucet() = submit { owner, skrMint ->
        buildList {
            if (!state.hasProfile) add(ClockInInstructions.createProfile(programId, owner))
            add(ClockInInstructions.createAssociatedTokenAccount(owner, owner, skrMint))
            add(
                ClockInInstructions.faucet(
                    programId, owner, skrMint, ClockInAddresses.associatedToken(owner, skrMint)
                )
            )
        }
    }

    fun stake(amount: Long) = submit { owner, skrMint ->
        buildList {
            // A wallet that already holds SKR may never have used the faucet.
            if (!state.hasProfile) add(ClockInInstructions.createProfile(programId, owner))
            add(ClockInInstructions.createAssociatedTokenAccount(owner, owner, skrMint))
            add(ClockInInstructions.stake(
                programId, owner, skrMint,
                ClockInAddresses.associatedToken(owner, skrMint), amount
            ))
        }
    }

    fun requestExit() = submit { owner, _ -> listOf(ClockInInstructions.requestExit(programId, owner)) }

    fun cancelExit() = submit { owner, _ -> listOf(ClockInInstructions.cancelExit(programId, owner)) }

    fun finalizeExit() = submit { owner, skrMint ->
        listOf(
            ClockInInstructions.finalizeExit(
                programId, owner, owner, skrMint,
                ClockInAddresses.associatedToken(owner, skrMint)
            )
        )
    }

    /** Publication exclusively authorized by the backend; retries reuse the durable encrypted packet. */
    fun publish(reviewAcknowledged: Boolean = false, caption: String = "", onSuccess: () -> Unit) {
        if (busy || feedLoading) return
        val session = wallet ?: return
        val owner = session.address ?: return
        busy = true; error = null
        viewModelScope.launch {
            try {
                val day = utcDay()
                val token = authenticate(session, owner)
                refreshNow(owner)
                var post = withContext(Dispatchers.IO) { pending.read(owner.base58(), day) }
                val firstAttempt = post == null
                if (state.posted) {
                    backend.confirm(state.todayCheckIn!!.commitment.hex(), token)
                } else {
                    if (post == null) {
                        val photos = draft ?: error("Prenez les deux photos avant de publier.")
                        check(moderation.allowsPublication(BuildConfig.DEBUG, reviewAcknowledged)) {
                            tr(Message.PhotoChecksMustFinishAndAllowPublishing)
                        }
                        val manifest = PostManifest.of(BuildConfig.NETWORK, programId.bytes, owner.bytes, day, photos.rear, photos.front)
                        val signature = session.signManifest(manifest).getOrThrow()
                        post = withContext(Dispatchers.IO) {
                            PostPacket.seal(manifest, signature, photos, caption.trim().take(DraftCodec.MAX_CAPTION_CHARS)).also { pending.write(owner.base58(), it) }
                        }
                        hasPendingPublication = true
                    }
                    val packet = requireNotNull(post)
                    val authority = SolanaPublicKey(requireNotNull(state.config).publicationAuthority)
                    val instruction = ClockInInstructions.checkIn(programId, owner, authority, day,
                        packet.commitment.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                        packet.blobRef.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                    val transaction = TransactionBuilder.build(listOf(instruction), owner, rpc.latestBlockhash())
                    // Wallet first: it may re-sort the accounts, so the server cosigns
                    // the exact message the wallet returned.
                    val signed = session.signTransaction(transaction.serialize()).getOrThrow()
                    val message = TransactionBuilder.messageBytes(signed)
                    require(TransactionBuilder.sameMeaning(message, transaction.message.serialize()))
                    val signers = TransactionBuilder.decode(message).signerKeys
                    val ownerIndex = signers.indexOf(owner.bytes.toList())
                    val authorityIndex = signers.indexOf(authority.bytes.toList())
                    require(ownerIndex == 0 && authorityIndex == 1)
                    verifySignature(owner.bytes, message, TransactionBuilder.signatureAt(signed, ownerIndex))
                    val authorized = try { backend.authorize(packet, signed, token) }
                    catch (failure: BackendException) {
                        // These responses precede reservation. Only discard on the initial
                        // attempt: a previous transport failure may already have reserved it.
                        if (firstAttempt && failure.status in listOf(400, 403, 422)) {
                            withContext(Dispatchers.IO) { pending.clear(owner.base58(), day) }
                            hasPendingPublication = false
                        }
                        throw failure
                    }
                    require(TransactionBuilder.messageBytes(authorized).contentEquals(message))
                    require(TransactionBuilder.signatureAt(authorized, ownerIndex).contentEquals(TransactionBuilder.signatureAt(signed, ownerIndex)))
                    verifySignature(authority.bytes, message, TransactionBuilder.signatureAt(authorized, authorityIndex))
                    val signature = rpc.sendTransaction(authorized)
                    check(rpc.awaitConfirmation(signature)) { tr(Message.TransactionSentButNotConfirmedWithinThe) }
                    backend.confirm(packet.commitment, token)
                }
                withContext(Dispatchers.IO) { pending.clear(owner.base58(), day) }
                hasPendingPublication = false
                this@ClockInModel.caption = caption.trim().take(DraftCodec.MAX_CAPTION_CHARS)
                saveDraft(draft, this@ClockInModel.caption)
                refreshNow(owner)
                onSuccess()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = messageFor(failure) }
            finally { busy = false; if (state.posted) loadFeed() }
        }
    }

    private fun submit(build: (SolanaPublicKey, SolanaPublicKey) -> List<TransactionInstruction>) {
        val owner = wallet?.address ?: run { error = WalletFailure.NotConnected.message; return }
        val skrMint = mint ?: run { error = MINT_MISSING; return }
        if (busy || feedLoading) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                sendSigned(build(owner, skrMint), owner)
                refreshNow(owner)
            } catch (failure: Exception) {
                error = messageFor(failure)
            } finally {
                busy = false
            }
        }
    }

    private suspend fun sendSigned(
        instructions: List<TransactionInstruction>,
        owner: SolanaPublicKey,
    ) {
        val session = wallet ?: throw WalletException(WalletFailure.NotConnected)
        val blockhash = rpc.latestBlockhash()
        val transaction = TransactionBuilder.build(instructions, owner, blockhash)
        val signed = session.signTransaction(transaction.serialize()).getOrThrow()
        val signedMessage = TransactionBuilder.messageBytes(signed)
        require(TransactionBuilder.sameMeaning(signedMessage, transaction.message.serialize()))
        verifySignature(owner.bytes, signedMessage, TransactionBuilder.signatureAt(signed, 0))
        val signature = rpc.sendTransaction(signed)
        check(rpc.awaitConfirmation(signature)) {
            tr(Message.TransactionSentButNotConfirmedWithinThe)
        }
    }

    private suspend fun refreshNow(owner: SolanaPublicKey) {
        val day = utcDay()
        if (state.day != day) { remoteFeed = emptyList(); feedCursor = null }
        val config = rpc.accountData(ClockInAddresses.config(programId), programId)
            ?.let(ClockInAccounts::decodeConfig)
        val profile = rpc.accountData(ClockInAddresses.profile(programId, owner), programId)
            ?.let(ClockInAccounts::decodeProfile)
        val checkIn = rpc.accountData(ClockInAddresses.checkIn(programId, owner, day), programId)
            ?.let(ClockInAccounts::decodeCheckIn)
        val balance = mint?.let { rpc.tokenBalance(ClockInAddresses.associatedToken(owner, it)) } ?: 0
        val saved = withContext(Dispatchers.IO) { pending.read(owner.base58(), day) }
        if (wallet?.address != owner) return
        require(profile == null || profile.owner.contentEquals(owner.bytes))
        require(checkIn == null || (checkIn.owner.contentEquals(owner.bytes) && checkIn.day == day))
        hasPendingPublication = saved != null
        if (authWallet != owner.base58()) { remoteFeed = emptyList(); feedCursor = null }
        if (state.day != day || checkIn == null) { remoteFeed = emptyList(); feedCursor = null }
        state = ChainState(config, profile, checkIn, balance, day, loaded = true)
    }

    private fun messageFor(failure: Throwable): String = run { android.util.Log.w("Moment", "Action echouee", failure) }.let { when (failure) {
        is BackendException -> { if (failure.status == 401) clearBackendSession(); failure.message.orEmpty() }
        is IllegalArgumentException -> "La configuration ou les données reçues sont invalides."
        is WalletException -> failure.failure.message
        else -> tr(Message.TransactionFailedCheckYourConnectionThenTry)
    } }

    // --- brouillon local chiffré ---

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun loadDraft() {
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                try {
                    if (!file.baseFile.exists()) return@withContext null
                    val sealed = file.openRead().use { it.readBytes() }
                    DraftCodec.decode(LocalEncryption.decrypt(sealed, keystoreKey()))
                } catch (_: Exception) {
                    null
                }
            }
            if (restored != null && restored.day == utcDay()) {
                if (draft == null) {
                    draft = restored.photos
                    caption = restored.caption
                    moderationCoordinator.replace(restored.photos)
                }
            }
        }
    }

    private suspend fun saveDraft(photos: PhotoPair?, caption: String) = withContext(Dispatchers.IO) {
        try {
            if (photos == null) {
                file.delete()
                return@withContext
            }
            val draftToStore = LocalDraft(utcDay(), photos, PostManifest.newNonce(), caption)
            val sealed = LocalEncryption.encrypt(DraftCodec.encode(draftToStore), keystoreKey())
            val output = file.startWrite()
            try {
                output.write(sealed)
                file.finishWrite(output)
            } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
        } catch (_: Exception) {
            // Le brouillon est un confort de reprise : son échec ne doit pas
            // interrompre une capture en cours.
        }
    }

    private companion object {
        const val KEY_ALIAS = "moment-local-v2"
        val MINT_MISSING get() =
            tr(Message.SkrMintIsNotConfiguredSetClockin)

        fun utcDay(): Long = Math.floorDiv(Instant.now().epochSecond, 86_400L)
    }
}
