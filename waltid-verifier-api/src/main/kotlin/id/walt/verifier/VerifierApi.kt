package id.walt.verifier

import id.walt.credentials.verification.PolicyManager
import id.walt.credentials.verification.models.PolicyRequest
import id.walt.credentials.verification.models.PolicyRequest.Companion.parsePolicyRequests
import id.walt.credentials.verification.policies.JwtSignaturePolicy
import id.walt.oid4vc.data.ResponseMode
import id.walt.oid4vc.data.dif.*
import id.walt.oid4vc.responses.TokenResponse
import id.walt.verifier.oidc.OIDCVerifierService
import id.walt.verifier.oidc.PresentationSessionInfo
import io.github.smiley4.ktorswaggerui.dsl.get
import io.github.smiley4.ktorswaggerui.dsl.post
import io.github.smiley4.ktorswaggerui.dsl.route
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
data class DescriptorMappingFormParam(val id: String, val format: VCFormat, val path: String)

@Serializable
data class PresentationSubmissionFormParam(
    val id: String, val definition_id: String, val descriptor_map: List<DescriptorMappingFormParam>
)

@Serializable
data class TokenResponseFormParam(
    val vp_token: JsonElement,
    val presentation_submission: PresentationSubmissionFormParam
)

@Serializable
data class CredentialVerificationRequest(
    @SerialName("vp_policies")
    val vpPolicies: List<JsonElement>,

    @SerialName("vc_policies")
    val vcPolicies: List<JsonElement>,

    @SerialName("request_credentials")
    val requestCredentials: List<JsonElement>
)

const val defaultAuthorizeBaseUrl = "openid4vp://authorize"

private val prettyJson = Json { prettyPrint = true }

val verifiableIdPresentationDefinitionExample = JsonObject(
    mapOf(
        "policies" to JsonArray(listOf(JsonPrimitive("signature"))),
        "presentation_definition" to
                PresentationDefinition(
                    "<automatically assigned>", listOf(
                        InputDescriptor(
                            "VerifiableId",
                            format = mapOf(VCFormat.jwt_vc_json to VCFormatDefinition(alg = setOf("EdDSA"))),
                            constraints = InputDescriptorConstraints(
                                fields = listOf(InputDescriptorField(path = listOf("$.type"), filter = buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("pattern", JsonPrimitive("VerifiableId"))
                                }))
                            )
                        )
                    )
                ).toJSON(),
    )
).let { prettyJson.encodeToString(it) }


fun Application.verfierApi() {
    routing {

        route("openid4vc", {
        }) {
            post("verify", {
                tags = listOf("Credential Verification")
                summary = "Initialize OIDC presentation session"
                description =
                    "Initializes an OIDC presentation session, with the given presentation definition and parameters. The URL returned can be rendered as QR code for the holder wallet to scan, or called directly on the holder if the wallet base URL is given."
                request {
                    headerParameter<String>("authorizeBaseUrl") {
                        description = "Base URL of wallet authorize endpoint, defaults to: $defaultAuthorizeBaseUrl"
                        example = defaultAuthorizeBaseUrl
                        required = false
                    }
                    headerParameter<ResponseMode>("responseMode") {
                        description = "Response mode, for vp_token response, defaults to ${ResponseMode.direct_post}"
                        example = ResponseMode.direct_post.name
                        required = false
                    }
                    headerParameter<String>("successRedirectUri") {
                        description = "Redirect URI to return when all policies passed. \"\$id\" will be replaced with the session id."
                        example = ""
                        required = false
                    }
                    headerParameter<String>("errorRedirectUri") {
                        description = "Redirect URI to return when a policy failed. \"\$id\" will be replaced with the session id."
                        example = ""
                        required = false
                    }
                    body<JsonObject> {
                        description =
                            "Presentation definition, describing the presentation requirement for this verification session. ID of the presentation definition is automatically assigned randomly."
                        //example("Verifiable ID example", verifiableIdPresentationDefinitionExample)
                        example("Minimal example", VerifierApiExamples.minimal)
                        example("Example with VP policies", VerifierApiExamples.vpPolicies)
                        example("Example with VP & global VC policies", VerifierApiExamples.vpGlobalVcPolicies)
                        example("Example with VP, VC & specific credential policies", VerifierApiExamples.vcVpIndividualPolicies)
                        example(
                            "Example with VP, VC & specific policies, and explicit presentation_definition  (maximum example)",
                            VerifierApiExamples.maxExample
                        )
                        example("Example with presentation definition policy", VerifierApiExamples.presentationDefinitionPolicy)
                    }
                }
            }) {
                val authorizeBaseUrl = context.request.header("authorizeBaseUrl") ?: defaultAuthorizeBaseUrl
                val responseMode =
                    context.request.header("responseMode")?.let { ResponseMode.valueOf(it) } ?: ResponseMode.direct_post
                val successRedirectUri = context.request.header("successRedirectUri")
                val errorRedirectUri = context.request.header("errorRedirectUri")


                val body = context.receive<JsonObject>()

                /*val presentationDefinition = (body["presentation_definition"]
                    ?: throw IllegalArgumentException("No `presentation_definition` supplied!"))
                    .let { PresentationDefinition.fromJSON(it.jsonObject) }*/

                val vpPolicies = body["vp_policies"]?.jsonArray?.parsePolicyRequests()
                    ?: listOf(PolicyRequest(JwtSignaturePolicy()))

                val vcPolicies = body["vc_policies"]?.jsonArray?.parsePolicyRequests()
                    ?: listOf(PolicyRequest(JwtSignaturePolicy()))

                val requestCredentialsArr = body["request_credentials"]!!.jsonArray

                val requestedTypes = requestCredentialsArr.map {
                    when (it) {
                        is JsonPrimitive -> it.contentOrNull
                        is JsonObject -> it["credential"]?.jsonPrimitive?.contentOrNull
                        else -> throw IllegalArgumentException("Invalid JSON type for requested credential: $it")
                    } ?: throw IllegalArgumentException("Invalid VC type for requested credential: $it")
                }

                val presentationDefinition = (body["presentation_definition"]?.let { PresentationDefinition.fromJSON(it.jsonObject) })
                    ?: PresentationDefinition.primitiveGenerationFromVcTypes(requestedTypes)
                println("Presentation definition: " + presentationDefinition.toJSON())

                val session =
                    OIDCVerifierService.initializeAuthorization(presentationDefinition, responseMode = responseMode)

                val specificPolicies = requestCredentialsArr
                    .filterIsInstance<JsonObject>()
                    .associate {
                        (it["credential"] ?: throw IllegalArgumentException("No `credential` name supplied, in `request_credentials`."))
                            .jsonPrimitive.content to (it["policies"]
                            ?: throw IllegalArgumentException("No `policies` supplied, in `request_credentials`."))
                            .jsonArray.parsePolicyRequests()
                    }

                println("vpPolicies: $vpPolicies")
                println("vcPolicies: $vcPolicies")
                println("spPolicies: $specificPolicies")


                OIDCVerifierService.sessionVerificationInfos[session.id] =
                    OIDCVerifierService.SessionVerificationInformation(
                        vpPolicies = vpPolicies,
                        vcPolicies = vcPolicies,
                        specificPolicies = specificPolicies,
                        successRedirectUri = successRedirectUri,
                        errorRedirectUri = errorRedirectUri
                    )

                context.respond(authorizeBaseUrl.plus("?").plus(session.authorizationRequest!!.toHttpQueryString()))
            }
            post("/verify/{state}", {
                tags = listOf("OIDC")
                summary = "Verify vp_token response, for a verification request identified by the state"
                description =
                    "Called in direct_post response mode by the SIOP provider (holder wallet) with the verifiable presentation in the vp_token and the presentation_submission parameter, describing the submitted presentation. The presentation session is identified by the given state parameter."
                request {
                    pathParameter<String>("state") {
                        description =
                            "State, i.e. session ID, identifying the presentation session, this response belongs to."
                        required = true
                    }
                    body<TokenResponseFormParam> {
                        mediaType(ContentType.Application.FormUrlEncoded)
                        example(
                            "simple vp_token response", TokenResponseFormParam(
                                JsonPrimitive("abc.def.ghi"), PresentationSubmissionFormParam(
                                    "1", "1", listOf(
                                        DescriptorMappingFormParam("1", VCFormat.jwt_vc_json, "$.type")
                                    )
                                )
                            )
                        )
                    }
                }
            }) {
                val session = call.parameters["state"]?.let { OIDCVerifierService.getSession(it) }
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        "State parameter doesn't refer to an existing session, or session expired"
                    )

                val tokenResponse = TokenResponse.fromHttpParameters(context.request.call.receiveParameters().toMap())
                val sessionVerificationInfo = OIDCVerifierService.sessionVerificationInfos[session.id]
                    ?: throw IllegalStateException("No session verification information found for session id!")

                val maybePresentationSessionResult = runCatching { OIDCVerifierService.verify(tokenResponse, session) }

                if (maybePresentationSessionResult.getOrNull() != null) {
                    val presentationSession = maybePresentationSessionResult.getOrThrow()
                    if (presentationSession.verificationResult == true) {
                        val redirectUri = sessionVerificationInfo.successRedirectUri?.replace("\$id", session.id) ?: ""
                        // insert VC data

                        call.respond(HttpStatusCode.OK, redirectUri)
                    } else {
                        val policyResults = OIDCVerifierService.policyResults[session.id]
                        val redirectUri = sessionVerificationInfo.errorRedirectUri?.replace("\$id", session.id)

                        if (redirectUri != null) {
                            call.respond(HttpStatusCode.BadRequest, redirectUri)
                        } else {
                            if (policyResults == null) {
                                call.respond(HttpStatusCode.BadRequest, "Verification policies did not succeed")
                            } else {
                                val failedPolicies =
                                    policyResults.results.flatMap { it.policyResults.map { it } }.filter { it.result.isFailure }
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    "Verification policies did not succeed: ${failedPolicies.joinToString { it.request.policy.name }}"
                                )
                            }
                        }
                    }
                } else {
                    call.respond(HttpStatusCode.BadRequest, "Verification failed")
                }
            }
            get("/session/{id}", {
                tags = listOf("Credential Verification")
                summary = "Get info about OIDC presentation session, that was previously initialized"
                description =
                    "Session info, containing current state and result information about an ongoing OIDC presentation session"
                request {
                    pathParameter<String>("id") {
                        description = "Session ID"
                        required = true
                    }
                }
                response {
                    HttpStatusCode.OK to {
                        description = "Session info"
                    }
                }
            }) {
                val id = call.parameters["id"] ?: throw IllegalArgumentException("No id provided!")
                val session = OIDCVerifierService.getSession(id)
                    ?: throw IllegalArgumentException("Invalid id provided (expired?): $id")

                val policyResults = OIDCVerifierService.policyResults[session.id]
                //?: throw IllegalStateException("No policy results found for id")

                call.respond(
                    Json { prettyPrint = true }.encodeToString(
                        PresentationSessionInfo.fromPresentationSession(
                            session,
                            policyResults?.toJson()
                        )
                    )
                )
            }
            get("/pd/{id}", {
                tags = listOf("OIDC")
                summary = "Get presentation definition object by ID"
                description =
                    "Gets a presentation definition object, previously uploaded during initialization of OIDC presentation session."
                request {
                    pathParameter<String>("id") {
                        description = "ID of presentation definition object to retrieve"
                        required = true
                    }
                }
            }) {
                val id = call.parameters["id"]
                val pd = id?.let { OIDCVerifierService.getSession(it)?.presentationDefinition }
                if (pd != null) {
                    call.respond(pd.toJSON())
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
            get("policy-list", {
                tags = listOf("Credential Verification")
                summary = "List registered policies"
                response { HttpStatusCode.OK to { body<Map<String, String?>>() } }
            }) {
                call.respond(PolicyManager.listPolicyDescriptions())
            }
        }
    }
}
