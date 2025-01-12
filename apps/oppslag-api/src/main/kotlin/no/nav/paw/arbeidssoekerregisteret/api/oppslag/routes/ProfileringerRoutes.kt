package no.nav.paw.arbeidssoekerregisteret.api.oppslag.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import no.nav.paw.arbeidssoekerregisteret.api.oppslag.models.Paging
import no.nav.paw.arbeidssoekerregisteret.api.oppslag.models.ProfileringRequest
import no.nav.paw.arbeidssoekerregisteret.api.oppslag.services.AuthorizationService
import no.nav.paw.arbeidssoekerregisteret.api.oppslag.services.PeriodeService
import no.nav.paw.arbeidssoekerregisteret.api.oppslag.services.ProfileringService
import no.nav.paw.arbeidssoekerregisteret.api.oppslag.utils.asUUID
import no.nav.paw.arbeidssoekerregisteret.api.oppslag.utils.buildApplicationLogger
import no.nav.paw.arbeidssoekerregisteret.api.oppslag.utils.getPidClaim
import no.nav.paw.arbeidssoekerregisteret.api.oppslag.utils.verifyAccessFromToken
import no.nav.paw.arbeidssoekerregisteret.api.oppslag.utils.verifyPeriodeId
import no.nav.paw.security.authentication.model.Identitetsnummer

private val logger = buildApplicationLogger

fun Route.profileringRoutes(
    authorizationService: AuthorizationService,
    periodeService: PeriodeService,
    profileringService: ProfileringService
) {
    route("/api/v1") {

        authenticate("tokenx") {
            get("/profilering") {
                val siste = call.request.queryParameters["siste"]?.toBoolean() ?: false
                val identitetsnummer = call.getPidClaim()
                val identitetsnummerList = authorizationService.finnIdentiteter(identitetsnummer)

                val paging = if (siste) Paging(size = 1) else Paging()
                val response = profileringService.finnProfileringerForIdentiteter(identitetsnummerList, paging)

                logger.info("Bruker hentet profilering")

                call.respond(HttpStatusCode.OK, response)
            }

            get("/profilering/{periodeId}") {
                val periodeId = call.parameters["periodeId"]?.asUUID()
                    ?: throw BadRequestException("Forespørsel mangler periodeId")
                val identitetsnummer = call.getPidClaim()
                val identitetsnummerList = authorizationService.finnIdentiteter(identitetsnummer)

                verifyPeriodeId(periodeId, identitetsnummerList, periodeService)

                val response = profileringService.finnProfileringerForPeriodeIdList(listOf(periodeId))

                logger.info("Bruker hentet profilering")

                call.respond(HttpStatusCode.OK, response)
            }
        }

        authenticate("azure") {
            post("/veileder/profilering") {
                val siste = call.request.queryParameters["siste"]?.toBoolean() ?: false
                val (identitetsnummer, periodeId) = call.receive<ProfileringRequest>()
                val identitetsnummerList = authorizationService.finnIdentiteter(Identitetsnummer(identitetsnummer))

                call.verifyAccessFromToken(authorizationService, identitetsnummerList)

                val paging = if (siste) Paging(size = 1) else Paging()
                val response = if (periodeId != null) {
                    verifyPeriodeId(periodeId, identitetsnummerList, periodeService)

                    profileringService.finnProfileringerForPeriodeIdList(listOf(periodeId), paging)
                } else {
                    profileringService.finnProfileringerForIdentiteter(identitetsnummerList, paging)
                }

                logger.info("Veileder hentet profilering for bruker")

                call.respond(HttpStatusCode.OK, response)
            }
        }
    }
}
