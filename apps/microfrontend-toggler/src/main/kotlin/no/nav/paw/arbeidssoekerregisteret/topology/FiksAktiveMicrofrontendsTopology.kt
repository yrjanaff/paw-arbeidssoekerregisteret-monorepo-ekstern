package no.nav.paw.arbeidssoekerregisteret.topology

import io.micrometer.core.instrument.MeterRegistry
import no.nav.paw.arbeidssoekerregisteret.config.buildPeriodeInfoSerde
import no.nav.paw.arbeidssoekerregisteret.config.buildToggleSerde
import no.nav.paw.arbeidssoekerregisteret.config.tellAntallToggles
import no.nav.paw.arbeidssoekerregisteret.context.ConfigContext
import no.nav.paw.arbeidssoekerregisteret.context.LoggingContext
import no.nav.paw.arbeidssoekerregisteret.model.PeriodeInfo
import no.nav.paw.arbeidssoekerregisteret.model.Toggle
import no.nav.paw.arbeidssoekerregisteret.model.buildDisableToggle
import no.nav.paw.arbeidssoekerregisteret.model.buildPeriodeInfo
import no.nav.paw.arbeidssoekerregisteret.model.buildRecord
import no.nav.paw.arbeidssokerregisteret.api.v1.Periode
import no.nav.paw.config.kafka.streams.Punctuation
import no.nav.paw.config.kafka.streams.filterWithContext
import no.nav.paw.config.kafka.streams.genericProcess
import no.nav.paw.config.kafka.streams.mapNonNull
import no.nav.paw.kafkakeygenerator.client.KafkaKeysResponse
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.kstream.Produced
import org.apache.kafka.streams.processor.PunctuationType
import org.apache.kafka.streams.state.KeyValueStore
import org.apache.kafka.streams.state.Stores
import java.time.Instant
import java.time.ZoneId

const val FIKS_AKTIVE_MICROFRONTENDS_TOGGLE_STATE_STORE = "fiksAktiveMicrofrontendsToggleStore"
private const val PERIODE_FILTER = "fiksAktiveMicrofrontendsTogglePeriodeFilter"
private const val PERIODE_INFO_MAPPER = "fiksAktiveMicrofrontendsTogglePeriodeInfoMapper"
private const val PROCESSOR = "fiksAktiveMicrofrontendsToggleProcessor"

private fun Periode.getStatus() = if (avsluttet == null) "aktiv" else "avsluttet"
private fun PeriodeInfo.getStatus() = if (avsluttet == null) "aktiv" else "avsluttet"

context(ConfigContext, LoggingContext)
private fun buildPunctuation(meterRegistry: MeterRegistry) = Punctuation<Long, Toggle>(
    appConfig.regler.fiksAktiveMicrofrontendsToggleSchedule, PunctuationType.WALL_CLOCK_TIME
) { _, context ->
    val deaktiveringsfrist =
        appConfig.regler.fiksAktiveMicrofrontendsForPerioderEldreEnn.atZone(ZoneId.systemDefault()).toInstant()
    val aiaMinSideAvsluttetfrist = deaktiveringsfrist.minus(appConfig.regler.utsattDeaktiveringAvAiaMinSide)
    val microfrontendConfig = appConfig.microfrontends

    val stateStore: KeyValueStore<Long, PeriodeInfo> =
        context.getStateStore(FIKS_AKTIVE_MICROFRONTENDS_TOGGLE_STATE_STORE)

    for (keyValue in stateStore.all()) {
        keyValue.value.let {
            val (id, _, arbeidssoekerId, _, avsluttet) = it

            if (avsluttet != null) {
                if (avsluttet.isBefore(deaktiveringsfrist)) {
                    if (avsluttet.isBefore(aiaMinSideAvsluttetfrist)) {
                        val disableAiaMinSideToggle = it.buildDisableToggle(microfrontendConfig.aiaMinSide)
                        meterRegistry.tellAntallToggles(disableAiaMinSideToggle)
                        context.forward(disableAiaMinSideToggle.buildRecord(arbeidssoekerId))
                        logger.info(
                            "Arbeidsøkerperiode {} ble avluttet mer enn 21 dager før frist {}. Iverksetter deaktivering av {}.",
                            id,
                            deaktiveringsfrist,
                            microfrontendConfig.aiaMinSide
                        )
                    } else {
                        logger.info(
                            "Arbeidsøkerperiode {} ble ikke avluttet mer enn 21 dager før frist {}. Utsetter deaktivering av {}.",
                            id,
                            deaktiveringsfrist,
                            microfrontendConfig.aiaMinSide
                        )
                    }

                    val disableAiaBehovsvurderingToggle = it.buildDisableToggle(microfrontendConfig.aiaBehovsvurdering)
                    meterRegistry.tellAntallToggles(disableAiaBehovsvurderingToggle)
                    context.forward(disableAiaBehovsvurderingToggle.buildRecord(arbeidssoekerId))
                    logger.info(
                        "Arbeidsøkerperiode {} ble avluttet før frist {}. Iverksetter deaktivering av {}.",
                        id,
                        deaktiveringsfrist,
                        microfrontendConfig.aiaBehovsvurdering
                    )

                } else {
                    logger.info(
                        "Arbeidsøkerperiode {} ble ikke avluttet før frist {}. Ignorerer.",
                        id,
                        deaktiveringsfrist
                    )
                }
            } else {
                logger.info(
                    "Arbeidsøkerperiode {} ble ikke avluttet før frist {}. Ignorerer.",
                    id,
                    deaktiveringsfrist
                )
            }

            logger.info("Sletter arbeidsøkerperiode {} fra state store.", id)
            stateStore.delete(arbeidssoekerId)
        }
    }
}

fun StreamsBuilder.addFiksAktiveMicrofrontendsStateStore() {
    this.addStateStore(
        Stores.keyValueStoreBuilder(
            Stores.persistentKeyValueStore(FIKS_AKTIVE_MICROFRONTENDS_TOGGLE_STATE_STORE),
            Serdes.Long(),
            buildPeriodeInfoSerde()
        )
    )
}

context(ConfigContext, LoggingContext)
fun StreamsBuilder.addFiksAktiveMicrofrontendsStream(
    meterRegistry: MeterRegistry, hentKafkaKeys: (ident: String) -> KafkaKeysResponse?
) {
    val kafkaStreamsConfig = appConfig.kafkaStreams
    val deaktiveringsfrist =
        appConfig.regler.fiksAktiveMicrofrontendsForPerioderEldreEnn.atZone(ZoneId.systemDefault()).toInstant()

    this.stream<Long, Periode>(kafkaStreamsConfig.periodeTopic).peek { key, _ ->
        logger.debug("Mottok event på {} med key {}", kafkaStreamsConfig.periodeTopic, key)
    }.filterWithContext(PERIODE_FILTER) { periode ->
        val streamTime = Instant.ofEpochMilli(this.currentStreamTimeMs())
        streamTime.isBefore(deaktiveringsfrist).also { before ->
            if (!before) logger.info(
                "Filtrerer ut {} arbeidssøkerperiode {} fordi stream time {} > {}",
                periode.getStatus(),
                periode.id,
                streamTime,
                deaktiveringsfrist
            )
        }
    }.mapNonNull(PERIODE_INFO_MAPPER) { periode ->
        hentKafkaKeys(periode.identitetsnummer)?.let {
            periode.buildPeriodeInfo(it.id)
        }
    }.genericProcess<Long, PeriodeInfo, Long, Toggle>(
        name = PROCESSOR,
        stateStoreNames = arrayOf(FIKS_AKTIVE_MICROFRONTENDS_TOGGLE_STATE_STORE),
        punctuation = buildPunctuation(meterRegistry)
    ) { record ->
        val streamTime = Instant.ofEpochMilli(this.currentStreamTimeMs())
        val periodeInfo = record.value()
        logger.info(
            "Lagrer {} arbeidssøkerperiode {} fordi stream time {} < {}",
            periodeInfo.getStatus(),
            periodeInfo.id,
            streamTime,
            deaktiveringsfrist
        )
        val stateStore: KeyValueStore<Long, PeriodeInfo> =
            getStateStore(FIKS_AKTIVE_MICROFRONTENDS_TOGGLE_STATE_STORE)
        stateStore.put(periodeInfo.arbeidssoekerId, periodeInfo)
    }.to(kafkaStreamsConfig.microfrontendTopic, Produced.with(Serdes.Long(), buildToggleSerde()))
}