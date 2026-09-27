package com.klementxv.moment

import com.klementxv.moment.i18n.Message
import com.klementxv.moment.i18n.tr
import com.klementxv.moment.i18n.AppLanguage

import android.app.Application
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.klementxv.moment.moderation.*
import com.klementxv.moment.capture.DraftCodec
import com.klementxv.moment.capture.LocalDraft
import com.klementxv.moment.capture.LocalEncryption
import com.klementxv.moment.capture.PhotoPair
import com.klementxv.moment.chain.Base58
import com.klementxv.moment.chain.CheckInAccount
import com.klementxv.moment.chain.MomentAccounts
import com.klementxv.moment.chain.MomentAddresses
import com.klementxv.moment.chain.MomentInstructions
import com.klementxv.moment.chain.ConfigAccount
import com.klementxv.moment.chain.DailyPool
import com.klementxv.moment.chain.DayPoolAccount
import com.klementxv.moment.chain.ProfileAccount
import com.klementxv.moment.chain.SolanaRpc
import com.klementxv.moment.chain.TransactionBuilder
import com.klementxv.moment.provenance.PostManifest
import com.klementxv.moment.backend.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import com.klementxv.moment.wallet.WalletException
import com.klementxv.moment.wallet.WalletFailure
import com.klementxv.moment.wallet.WalletSession
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.time.Instant
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

const val SKR: Long = 1_000_000_000L

data class ChainState(
    val config: ConfigAccount? = null,
    val profile: ProfileAccount? = null,
    val todayCheckIn: CheckInAccount? = null,
    val tokenBalance: Long = 0,
    val day: Long = 0,
    val loaded: Boolean = false,
    val pools: Map<Long, DayPoolAccount> = emptyMap(),
    val now: Long = 0,
    val skrDecimals: Int? = null,
) {
    val canStake: Boolean get() = skrDecimals != null

    val balance: Long
        get() = if (profile == null || config == null) {
            0
        } else {
            profile.settledBalance(config.decayBps, config.maxDecayDays, day, closedGain)
        }

    val minStake: Long get() = config?.minStake ?: (500 * SkrUnit.unit)

    val decayBps: Int get() = config?.decayBps ?: 1_000
    val streak: Long get() = profile?.streak ?: 0
    val totalCheckIns: Long get() = profile?.totalCheckIns ?: 0
    val active: Boolean get() = profile?.active == true
    val hasProfile: Boolean get() = profile != null
    val faucetClaimed: Boolean get() = profile?.faucetClaimed == true
    val exitUnlockAt: Long get() = profile?.exitUnlockAt ?: 0
    val posted: Boolean get() = todayCheckIn != null

    private val closeDelay: Long get() = config?.poolCloseDelaySeconds ?: 21_600

    private fun claims(closed: Boolean): List<Pair<Long, Long>> = profile?.let { p ->
        p.pendingDays.zip(p.pendingStakes).filter { (claimDay, _) ->
            claimDay != DailyPool.NO_DAY && (now >= DailyPool.closesAt(claimDay, closeDelay)) == closed
        }
    } ?: emptyList()

    private fun gain(claims: List<Pair<Long, Long>>): Long = claims.sumOf { (claimDay, stake) ->
        pools[claimDay]?.let { DailyPool.share(it.penalties, stake, it.totalStake) } ?: 0
    }

    val closedGain: Long get() = gain(claims(closed = true))

    val pendingGain: Long get() = gain(claims(closed = false))

    val payoutAt: Long? get() = claims(closed = false).minOfOrNull { (claimDay, _) -> DailyPool.closesAt(claimDay, closeDelay) }

    val todayPoolTotal: Long get() = pools[day]?.penalties ?: 0

    val myShareToday: Long get() {
        val pool = pools[day] ?: return 0
        val slot = profile?.pendingDays?.indexOf(day) ?: -1
        return if (slot >= 0) DailyPool.share(pool.penalties, profile!!.pendingStakes[slot], pool.totalStake)
        else DailyPool.share(pool.penalties, balance, pool.totalStake + balance)
    }

    val feedUnlocked: Boolean get() = posted

    fun canPublish(now: Long): Boolean =
        !posted && active && balance >= minStake && (exitUnlockAt == 0L || now < exitUnlockAt)
}

class MomentModel(application: Application) : AndroidViewModel(application) {
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
    private val sessionStore = application.getSharedPreferences("backend-session", android.content.Context.MODE_PRIVATE)
    private var authToken: String? = sessionStore.getString("token", null)
    private var authWallet: String? = sessionStore.getString("wallet", null)
    private var authExpires = sessionStore.getLong("expires", 0L)
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
        get() = storedError?.let { com.klementxv.moment.i18n.localizeError(it) }
        private set(value) { storedError = value }
    var draft by mutableStateOf<PhotoPair?>(null)
        private set
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
            if (!state.hasProfile) add(MomentInstructions.createProfile(programId, owner))
            add(MomentInstructions.createAssociatedTokenAccount(owner, owner, skrMint))
            add(
                MomentInstructions.faucet(
                    programId, owner, skrMint, MomentAddresses.associatedToken(owner, skrMint)
                )
            )
        }
    }

    fun stake(amount: Long) {
        if (!state.canStake) { error = tr(Message.SkrUnitNotReadYet); return }
        stakeNow(amount)
    }

    private fun stakeNow(amount: Long) = submit { owner, skrMint ->
        buildList {
            if (!state.hasProfile) add(MomentInstructions.createProfile(programId, owner))
            add(MomentInstructions.createAssociatedTokenAccount(owner, owner, skrMint))
            val day = utcDay()
            val pools = state.profile?.takeIf { it.active }
                ?.let { settlementPools(DailyPool.settleBound(it, day)) } ?: emptyList()
            add(MomentInstructions.stake(
                programId, owner, skrMint,
                MomentAddresses.associatedToken(owner, skrMint), day, amount, pools
            ))
        }
    }

    fun requestExit() = submit { owner, _ ->
        val day = utcDay()
        val bound = state.profile?.let { DailyPool.settleBound(it, day) } ?: (day - 1)
        listOf(MomentInstructions.requestExit(programId, owner, day, settlementPools(bound)))
    }

    fun cancelExit() = submit { owner, _ -> listOf(MomentInstructions.cancelExit(programId, owner)) }

    fun finalizeExit() = submit { owner, skrMint ->
        val bound = Math.floorDiv(state.exitUnlockAt, 86_400L) - 1
        listOf(
            MomentInstructions.finalizeExit(
                programId, owner, owner, skrMint,
                MomentAddresses.associatedToken(owner, skrMint), utcDay(), settlementPools(bound)
            )
        )
    }

    private fun settlementPools(bound: Long): List<AccountMeta> =
        DailyPool.poolMetas(programId, state.profile, bound)

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
                    val bound = state.profile?.let { DailyPool.settleBound(it, day) } ?: (day - 1)
                    val instruction = MomentInstructions.checkIn(programId, owner, authority, day,
                        packet.commitment.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                        packet.blobRef.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                        settlementPools(bound))
                    val transaction = TransactionBuilder.build(listOf(instruction), owner, rpc.latestBlockhash())
                    val signed = session.signTransaction(transaction.serialize()).getOrThrow()
                    val message = TransactionBuilder.messageBytes(signed)
                    require(TransactionBuilder.sameMeaning(message, transaction.message.serialize()))
                    val signers = TransactionBuilder.decode(message).signerKeys
                    val ownerIndex = signers.indexOf(owner.bytes.toList())
                    val authorityIndex = signers.indexOf(authority.bytes.toList())
                    require(ownerIndex == 0 && authorityIndex == 1)
                    verifySignature(owner.bytes, message, TransactionBuilder.signatureAt(signed, ownerIndex))
                    val authorized = authorizePendingPost(
                        firstAttempt = firstAttempt,
                        authorize = { backend.authorize(packet, signed, token) },
                        discard = {
                            withContext(Dispatchers.IO) { pending.clear(owner.base58(), day) }
                            hasPendingPublication = false
                        },
                    )
                    require(TransactionBuilder.messageBytes(authorized).contentEquals(message))
                    require(TransactionBuilder.signatureAt(authorized, ownerIndex).contentEquals(TransactionBuilder.signatureAt(signed, ownerIndex)))
                    verifySignature(authority.bytes, message, TransactionBuilder.signatureAt(authorized, authorityIndex))
                    val signature = rpc.sendTransaction(authorized)
                    check(rpc.awaitConfirmation(signature)) { tr(Message.TransactionSentButNotConfirmedWithinThe) }
                    backend.confirm(packet.commitment, token)
                }
                withContext(Dispatchers.IO) { pending.clear(owner.base58(), day) }
                hasPendingPublication = false
                this@MomentModel.caption = caption.trim().take(DraftCodec.MAX_CAPTION_CHARS)
                saveDraft(draft, this@MomentModel.caption)
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
        val config = rpc.accountData(MomentAddresses.config(programId), programId)
            ?.let(MomentAccounts::decodeConfig)
        val profile = rpc.accountData(MomentAddresses.profile(programId, owner), programId)
            ?.let(MomentAccounts::decodeProfile)
        val checkIn = rpc.accountData(MomentAddresses.checkIn(programId, owner, day), programId)
            ?.let(MomentAccounts::decodeCheckIn)
        val pools = DailyPool.poolDays(profile, day).mapNotNull { poolDay ->
            rpc.accountData(MomentAddresses.dayPool(programId, poolDay), programId)
                ?.let(MomentAccounts::decodeDayPool)?.let { poolDay to it }
        }.toMap()
        val balance = mint?.let { rpc.tokenBalance(MomentAddresses.associatedToken(owner, it)) } ?: 0
        val skrDecimals = mint?.let { rpc.accountData(it, MomentInstructions.TOKEN_PROGRAM) }
            ?.let(MomentAccounts::decodeMintDecimals)
        val saved = withContext(Dispatchers.IO) { pending.read(owner.base58(), day) }
        if (wallet?.address != owner) return
        require(profile == null || profile.owner.contentEquals(owner.bytes))
        require(checkIn == null || (checkIn.owner.contentEquals(owner.bytes) && checkIn.day == day))
        hasPendingPublication = saved != null
        if (authWallet != owner.base58()) { remoteFeed = emptyList(); feedCursor = null }
        if (state.day != day || checkIn == null) { remoteFeed = emptyList(); feedCursor = null }
        skrDecimals?.let { SkrUnit.decimals = it }
        state = ChainState(config, profile, checkIn, balance, day, loaded = true,
            pools = pools, now = Instant.now().epochSecond, skrDecimals = skrDecimals)
    }

    private fun messageFor(failure: Throwable): String = run { android.util.Log.w("Moment", "Action echouee", failure) }.let { when (failure) {
        is BackendException -> { if (failure.status == 401) clearBackendSession(); failure.message.orEmpty() }
        is IllegalArgumentException -> "La configuration ou les données reçues sont invalides."
        is WalletException -> failure.failure.message
        else -> tr(Message.TransactionFailedCheckYourConnectionThenTry)
    } }


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
        }
    }

    private companion object {
        const val KEY_ALIAS = "moment-local-v2"
        val MINT_MISSING get() =
            tr(Message.SkrMintIsNotConfigured)

        fun utcDay(): Long = Math.floorDiv(Instant.now().epochSecond, 86_400L)
    }
}
