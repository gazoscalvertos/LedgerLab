package com.ledgerlab.xrpl.wallet

import org.xrpl.xrpl4j.codec.addresses.AddressCodec
import org.xrpl.xrpl4j.codec.addresses.KeyType
import org.xrpl.xrpl4j.codec.addresses.SeedCodec
import org.xrpl.xrpl4j.crypto.keys.Base58EncodedSecret
import org.xrpl.xrpl4j.crypto.keys.PublicKey
import org.xrpl.xrpl4j.crypto.keys.Seed
import org.xrpl.xrpl4j.crypto.signing.MultiSignedTransaction
import org.xrpl.xrpl4j.crypto.signing.Signature
import org.xrpl.xrpl4j.crypto.signing.bc.BcSignatureService
import org.xrpl.xrpl4j.model.transactions.Signer
import org.xrpl.xrpl4j.model.client.channels.UnsignedClaim
import org.xrpl.xrpl4j.model.transactions.Address
import org.xrpl.xrpl4j.model.transactions.Hash256
import org.xrpl.xrpl4j.model.transactions.Transaction
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount

enum class KeyAlgorithm(val label: String) {
    ED25519("Ed25519"),
    SECP256K1("secp256k1"),
}

/** A key pair's public half plus the family seed ("secret") that recreates it. */
data class WalletKeyInfo(
    val address: String,
    val publicKey: String,
    val secret: String,
    val algorithm: KeyAlgorithm,
)

data class SignedTx(val blob: String, val hash: String)

/** One signer's approval of a multi-signed transaction. */
data class SignerShare(val address: String, val publicKey: String, val signature: String)

/** Key generation, import and signing, all done on the device with xrpl4j. Secrets never leave the phone. */
object WalletKeys {
    private val signer by lazy { BcSignatureService() }

    fun generate(algorithm: KeyAlgorithm = KeyAlgorithm.ED25519): WalletKeyInfo {
        val seed = when (algorithm) {
            KeyAlgorithm.ED25519 -> Seed.ed25519Seed()
            KeyAlgorithm.SECP256K1 -> Seed.secp256k1Seed()
        }
        val decoded = seed.decodedSeed()
        val keyType = decoded.type().orElse(if (algorithm == KeyAlgorithm.ED25519) KeyType.ED25519 else KeyType.SECP256K1)
        return fromSecret(SeedCodec.getInstance().encodeSeed(decoded.bytes(), keyType))
    }

    /** Imports a family seed such as `sEd…` (Ed25519) or `s…` (secp256k1). Throws [IllegalArgumentException] if invalid. */
    fun fromSecret(secret: String): WalletKeyInfo {
        val trimmed = secret.trim()
        val seed = try {
            Seed.fromBase58EncodedSecret(Base58EncodedSecret.of(trimmed))
        } catch (e: Exception) {
            throw IllegalArgumentException("That doesn't look like an XRPL secret (it should start with \"s\").", e)
        }
        val keyPair = seed.deriveKeyPair()
        val publicKey = keyPair.publicKey()
        return WalletKeyInfo(
            address = publicKey.deriveAddress().value(),
            publicKey = publicKey.base16Value(),
            secret = trimmed,
            algorithm = if (trimmed.startsWith("sEd")) KeyAlgorithm.ED25519 else KeyAlgorithm.SECP256K1,
        )
    }

    fun isValidAddress(address: String): Boolean =
        runCatching { AddressCodec.getInstance().isValidClassicAddress(Address.of(address.trim())) }.getOrDefault(false)

    /**
     * Builds a transaction with [build] (given the signer's address and public key) and signs it.
     * Returns the hex blob ready for `submit` and the transaction hash.
     */
    fun <T : Transaction> sign(secret: String, build: (Address, PublicKey) -> T): SignedTx {
        val keyPair = Seed.fromBase58EncodedSecret(Base58EncodedSecret.of(secret.trim())).deriveKeyPair()
        val tx = build(keyPair.publicKey().deriveAddress(), keyPair.publicKey())
        val signed = signer.sign(keyPair.privateKey(), tx)
        return SignedTx(signed.signedTransactionBytes().hexValue(), signed.hash().value())
    }

    /**
     * Signs a payment channel claim off the ledger: "the payee may take [drops] in total from [channelId]".
     * Returns the hex signature. Nothing is submitted, so this is free and instant.
     */
    fun signClaim(secret: String, channelId: String, drops: Long): String {
        val keyPair = Seed.fromBase58EncodedSecret(Base58EncodedSecret.of(secret.trim())).deriveKeyPair()
        val claim = UnsignedClaim.builder()
            .channel(Hash256.of(channelId))
            .amount(XrpCurrencyAmount.ofDrops(drops))
            .build()
        return signer.sign(keyPair.privateKey(), claim).base16Value()
    }

    /** Signs [tx] as one of a multi-signed account's signers. Nothing is submitted. */
    fun multiSign(secret: String, tx: Transaction): SignerShare {
        val keyPair = Seed.fromBase58EncodedSecret(Base58EncodedSecret.of(secret.trim())).deriveKeyPair()
        val signature = signer.multiSign(keyPair.privateKey(), tx)
        return SignerShare(keyPair.publicKey().deriveAddress().value(), keyPair.publicKey().base16Value(), signature.base16Value())
    }

    /** Combines the signers' approvals into one blob ready for `submit`. */
    fun <T : Transaction> assembleMultiSigned(tx: T, shares: Collection<SignerShare>): SignedTx {
        val signed = MultiSignedTransaction.builder<T>()
            .unsignedTransaction(tx)
            .signerSet(
                shares.map {
                    Signer.builder()
                        .signingPublicKey(PublicKey.fromBase16EncodedPublicKey(it.publicKey))
                        .transactionSignature(Signature.fromBase16(it.signature))
                        .build()
                }.toSet(),
            )
            .build()
        return SignedTx(signed.signedTransactionBytes().hexValue(), signed.hash().value())
    }
}
