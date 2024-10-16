package no.nav.paw.test.data.periode

import no.nav.paw.arbeidssokerregisteret.api.v1.Bruker
import no.nav.paw.arbeidssokerregisteret.api.v1.BrukerType

class BrukerFactory private constructor() {

    fun build(
        id: String = "01017012345",
        brukerType: BrukerType = BrukerType.SLUTTBRUKER,
    ) = Bruker(brukerType, id)

    companion object {
        fun create() = BrukerFactory()
    }
}