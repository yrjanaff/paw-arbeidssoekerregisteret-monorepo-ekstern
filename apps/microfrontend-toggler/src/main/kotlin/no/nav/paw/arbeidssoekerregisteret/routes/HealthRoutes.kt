package no.nav.paw.arbeidssoekerregisteret.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.micrometer.prometheus.PrometheusMeterRegistry
import no.nav.paw.arbeidssoekerregisteret.config.HealthIndicator
import no.nav.paw.arbeidssoekerregisteret.model.HealthStatus

fun Route.healthRoutes(
    healthIndicator: HealthIndicator,
    meterRegistry: PrometheusMeterRegistry
) {

    get("/internal/isAlive") {
        call.respondText(HealthStatus.HEALTHY.value, ContentType.Text.Plain)
    }

    get("/internal/isReady") {
        when (val status = healthIndicator.getStatus()) {
            HealthStatus.HEALTHY -> call.respondText(
                ContentType.Text.Plain,
                HttpStatusCode.OK
            ) { status.value }

            else -> call.respondText(
                ContentType.Text.Plain,
                HttpStatusCode.ServiceUnavailable
            ) { status.value }
        }
    }

    get("/internal/metrics") {
        call.respond(meterRegistry.scrape())
    }
}