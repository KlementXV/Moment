package com.klementxv.moment.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class DevPublicationAuthorityTest {
    @Test
    fun signs_the_rfc_8032_empty_message_vector() {
        val key = hex(
            "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60" +
                "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        )
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            Ed25519.sign(ByteArray(0), key).joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun signs_the_rfc_8032_single_byte_vector() {
        val key = hex(
            "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb" +
                "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
        )
        assertEquals(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            Ed25519.sign(byteArrayOf(0x72), key).joinToString("") { "%02x".format(it) },
        )
    }

    private fun hex(value: String) = ByteArray(value.length / 2) {
        value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
