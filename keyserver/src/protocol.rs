//! Wire formats shared with Android. Never serialize the signed manifest as JSON.
use crate::error::{Error, Result};
use aes_gcm::{
    aead::{Aead, KeyInit, Payload},
    Aes256Gcm, Nonce,
};
use base64::{
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
    Engine,
};
use ed25519_dalek::{Signature, VerifyingKey};
use rand::{rngs::OsRng, RngCore};
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

pub const PACKET_DOMAIN: &[u8] = b"moment-post-v1";
pub const MANIFEST_DOMAIN: &[u8] = b"clockin-post-v1";
pub const MAX_PHOTO: usize = 4 * 1024 * 1024;
pub const MAX_BLOB: usize = 2 * MAX_PHOTO + 4096;
pub type Key = [u8; 32];

pub fn hash(bytes: &[u8]) -> Key {
    Sha256::digest(bytes).into()
}
pub fn b64(bytes: &[u8]) -> String {
    STANDARD.encode(bytes)
}
pub fn unbase64(value: &str, max: usize) -> Result<Vec<u8>> {
    if value.len() > max.div_ceil(3) * 4 {
        return Err(Error::bad(
            "Données trop volumineuses. Reprenez les photos.",
        ));
    }
    let bytes = STANDARD
        .decode(value)
        .map_err(|_| Error::bad("Encodage base64 invalide. Actualisez l’application."))?;
    if bytes.len() > max {
        return Err(Error::bad(
            "Données trop volumineuses. Reprenez les photos.",
        ));
    }
    Ok(bytes)
}
pub fn key64(value: &str) -> Result<Key> {
    unbase64(value, 32)?
        .try_into()
        .map_err(|_| Error::bad("Clé invalide. Reprenez la publication."))
}
pub fn address(value: &str) -> Result<Key> {
    if value.len() > 44 {
        return Err(Error::bad(
            "Adresse wallet invalide. Reconnectez le wallet.",
        ));
    }
    bs58::decode(value)
        .into_vec()
        .ok()
        .and_then(|b| b.try_into().ok())
        .ok_or_else(|| Error::bad("Adresse wallet invalide. Reconnectez le wallet."))
}
pub fn address_string(value: &Key) -> String {
    bs58::encode(value).into_string()
}
pub fn hash_hex(value: &str) -> Result<Key> {
    if value.len() != 64
        || !value
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
    {
        return Err(Error::bad("Référence invalide. Actualisez le Moment."));
    }
    hex::decode(value)
        .ok()
        .and_then(|v| v.try_into().ok())
        .ok_or_else(Error::internal)
}
pub fn random_token() -> String {
    let mut bytes = [0; 32];
    OsRng.fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}
pub fn verify(wallet: &Key, message: &[u8], signature: &[u8]) -> Result<()> {
    let key = VerifyingKey::from_bytes(wallet).map_err(|_| Error::auth())?;
    let sig = Signature::from_slice(signature).map_err(|_| Error::auth())?;
    key.verify_strict(message, &sig).map_err(|_| Error::auth())
}
pub fn seal(plain: &[u8], key: &Key, aad: &[u8]) -> Result<Vec<u8>> {
    let mut iv = [0; 12];
    OsRng.fill_bytes(&mut iv);
    let cipher = Aes256Gcm::new(key.into());
    let body = cipher
        .encrypt(Nonce::from_slice(&iv), Payload { msg: plain, aad })
        .map_err(|_| Error::internal())?;
    Ok([iv.as_slice(), &body].concat())
}
pub fn open(blob: &[u8], key: &Key, aad: &[u8]) -> Result<Zeroizing<Vec<u8>>> {
    if blob.len() < 28 {
        return Err(Error::bad(
            "Paquet chiffré tronqué. Reprenez la publication.",
        ));
    }
    Aes256Gcm::new(key.into())
        .decrypt(
            Nonce::from_slice(&blob[..12]),
            Payload {
                msg: &blob[12..],
                aad,
            },
        )
        .map(Zeroizing::new)
        .map_err(|_| Error::bad("Paquet chiffré invalide. Reprenez la publication."))
}

pub struct Reader<'a> {
    bytes: &'a [u8],
    offset: usize,
}
impl<'a> Reader<'a> {
    pub fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, offset: 0 }
    }
    pub fn take(&mut self, len: usize) -> Result<&'a [u8]> {
        let end = self
            .offset
            .checked_add(len)
            .ok_or_else(|| Error::bad("Format invalide. Actualisez l’application."))?;
        let out = self
            .bytes
            .get(self.offset..end)
            .ok_or_else(|| Error::bad("Données tronquées. Réessayez."))?;
        self.offset = end;
        Ok(out)
    }
    pub fn array<const N: usize>(&mut self) -> Result<[u8; N]> {
        Ok(self.take(N)?.try_into().expect("checked length"))
    }
    pub fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }
    pub fn i64(&mut self) -> Result<i64> {
        Ok(i64::from_le_bytes(self.array()?))
    }
    pub fn u64(&mut self) -> Result<u64> {
        Ok(u64::from_le_bytes(self.array()?))
    }
    pub fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_le_bytes(self.array()?))
    }
    pub fn done(&self) -> bool {
        self.offset == self.bytes.len()
    }
    pub fn position(&self) -> usize {
        self.offset
    }
    pub fn short(&mut self) -> Result<usize> {
        let mut value = 0usize;
        for i in 0..3 {
            let b = self.u8()?;
            if (i == 2 && b > 3) || (i > 0 && b == 0) {
                return Err(Error::bad("Transaction non canonique. Reconstruisez-la."));
            }
            value |= ((b & 127) as usize) << (7 * i);
            if b & 128 == 0 {
                return Ok(value);
            }
        }
        Err(Error::bad("Transaction invalide. Reconstruisez-la."))
    }
}

pub struct Packet {
    pub manifest: Vec<u8>,
    pub signature: [u8; 64],
    pub rear: Zeroizing<Vec<u8>>,
    pub front: Zeroizing<Vec<u8>>,
}
impl Packet {
    pub fn decrypt(blob: &[u8], key: &Key) -> Result<Self> {
        if blob.len() > MAX_BLOB {
            return Err(Error::bad("Photos trop volumineuses. Reprenez-les."));
        }
        let plain = open(blob, key, PACKET_DOMAIN)?;
        let mut r = Reader::new(&plain);
        if r.take(PACKET_DOMAIN.len())? != PACKET_DOMAIN || r.u8()? != 1 {
            return Err(Error::bad(
                "Version de paquet inconnue. Actualisez l’application.",
            ));
        }
        fn field<'a>(r: &mut Reader<'a>, max: usize) -> Result<&'a [u8]> {
            let n = u32::from_le_bytes(r.array()?) as usize;
            if n == 0 || n > max {
                return Err(Error::bad(
                    "Champ de paquet invalide. Reprenez la publication.",
                ));
            }
            r.take(n)
        }
        let manifest = field(&mut r, 1024)?.to_vec();
        let signature = field(&mut r, 64)?
            .try_into()
            .map_err(|_| Error::bad("Signature invalide. Signez à nouveau."))?;
        let rear = Zeroizing::new(field(&mut r, MAX_PHOTO)?.to_vec());
        let front = Zeroizing::new(field(&mut r, MAX_PHOTO)?.to_vec());
        if !r.done() {
            return Err(Error::bad(
                "Paquet non canonique. Actualisez l’application.",
            ));
        }
        Ok(Self {
            manifest,
            signature,
            rear,
            front,
        })
    }
    pub fn verify(&self, network: &str, program: &Key, wallet: &Key, day: i64) -> Result<Key> {
        let mut r = Reader::new(&self.manifest);
        if r.take(MANIFEST_DOMAIN.len())? != MANIFEST_DOMAIN || r.u8()? != 1 {
            return Err(Error::bad("Manifeste inconnu. Actualisez l’application."));
        }
        let n = r.u8()? as usize;
        if n == 0
            || n > 32
            || r.take(n)? != network.as_bytes()
            || r.array::<32>()? != *program
            || r.array::<32>()? != *wallet
            || r.i64()? != day
        {
            return Err(Error::bad("Le manifeste ne correspond pas au wallet, au réseau ou au jour. Reprenez la publication."));
        }
        r.take(16)?;
        if r.array::<32>()? != hash(&self.rear)
            || r.array::<32>()? != hash(&self.front)
            || !r.done()
        {
            return Err(Error::bad(
                "Les photos ne correspondent pas au manifeste. Reprenez la publication.",
            ));
        }
        verify(wallet, &self.manifest, &self.signature)?;
        Ok(hash(&self.manifest))
    }
}
