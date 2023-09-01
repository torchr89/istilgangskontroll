package no.nav.syfo.client.pdl

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import net.logstash.logback.argument.StructuredArguments
import no.nav.syfo.application.api.auth.Token
import no.nav.syfo.client.azuread.AzureAdClient
import no.nav.syfo.client.httpClientDefault
import no.nav.syfo.domain.Personident
import no.nav.syfo.util.*
import org.slf4j.LoggerFactory

class PdlClient(
    private val azureAdClient: AzureAdClient,
    private val baseUrl: String,
    private val clientId: String,
    private val httpClient: HttpClient = httpClientDefault(),
) {

    suspend fun person(
        callId: String,
        personident: Personident,
        token: Token,
    ): PdlHentPerson {
        val request = PdlRequest(
            query = getPdlQuery("/pdl/hentPerson.graphql"),
            variables = Variables(personident.value),
        )
        val oboToken = azureAdClient.getOnBehalfOfToken(
            scopeClientId = clientId,
            token = token,
            callId = callId
        )?.accessToken
            ?: throw RuntimeException("Failed to request person info from pdl: Failed to get token from AzureAD with callId=$callId")
        try {
            val response: HttpResponse = httpClient.post(baseUrl) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, bearerHeader(oboToken))
                header(TEMA_HEADER, ALLE_TEMA_HEADERVERDI)
                header(NAV_CALL_ID_HEADER, callId)
                setBody(request)
            }

            when (response.status) {
                HttpStatusCode.OK -> {
                    val pdlResponse = response.body<PdlPersonResponse>()
                    return getPdlHentPerson(pdlResponse) ?: throw RuntimeException("Failed to get person info from PDL callId=$callId")
                }

                else -> {
                    COUNT_CALL_PDL_PERSON_FAIL.increment()
                    log.error("Request with url: $baseUrl failed with reponse code ${response.status.value}")
                    throw RuntimeException("Request with url: $baseUrl failed with reponse code ${response.status.value}")
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            COUNT_CALL_PDL_PERSON_FAIL.increment()
            throw RuntimeException("Caught ClosedReceiveChannelException in PdlClient.person", e)
        } catch (e: ResponseException) {
            COUNT_CALL_PDL_PERSON_FAIL.increment()
            log.error(
                "Error while requesting Person from PersonDataLosningen {}, {}, {}",
                StructuredArguments.keyValue("statusCode", e.response.status.value.toString()),
                StructuredArguments.keyValue("message", e.message),
                callIdArgument(callId),
            )
            throw e
        }
    }

    private fun getPdlHentPerson(pdlResponse: PdlPersonResponse): PdlHentPerson? {
        return if (!pdlResponse.errors.isNullOrEmpty()) {
            COUNT_CALL_PDL_PERSON_FAIL.increment()
            pdlResponse.errors.forEach {
                log.error("Error while requesting person from PersonDataLosningen: ${it.errorMessage()}")
            }
            null
        } else {
            COUNT_CALL_PDL_PERSON_SUCCESS.increment()
            pdlResponse.data
        }
    }

    private fun getPdlQuery(queryFilePath: String): String {
        return this::class.java.getResource(queryFilePath)
            .readText()
            .replace("[\n\r]", "")
    }

    companion object {
        private val log = LoggerFactory.getLogger(PdlClient::class.java)
    }
}