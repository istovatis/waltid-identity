package id.walt.issuer

import id.walt.credentials.vc.vcs.W3CVC
import id.walt.sdjwt.SDMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

sealed class BaseIssuanceRequest {
    abstract val issuanceKey: JsonObject
    abstract val issuerDid: String

    abstract val vc: W3CVC
    abstract val mapping: JsonObject?
}

@Serializable
data class JwtIssuanceRequest(
    override val issuanceKey: JsonObject,
    override val issuerDid: String,

    override val vc: W3CVC,
    override val mapping: JsonObject? = null
) : BaseIssuanceRequest()

@Serializable
data class JwtEmfisisIssuanceRequest(
    val issuanceKey: JsonObject,
    val issuerDid: String,
    val username: String,
    val mapping: JsonObject? = null
)

@Serializable
data class SdJwtIssuanceRequest(
    override val issuanceKey: JsonObject,
    override val issuerDid: String,

    override val vc: W3CVC,
    override val mapping: JsonObject? = null,
    val selectiveDisclosure: SDMap? = null,
) : BaseIssuanceRequest()
