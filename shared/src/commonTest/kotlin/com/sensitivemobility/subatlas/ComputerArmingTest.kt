package com.sensitivemobility.subatlas

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerArmingTest {
    @Test
    fun aComputerThatStaysInRangeIsImportedOnce() {
        val watch = ComputerArming()
        assertTrue(watch.sighting(true))
        repeat(5) { assertFalse(watch.sighting(true)) }
    }

    @Test
    fun anImportStartedElsewhereIsNotRepeated() {
        // L'association ou le bouton ont déjà importé : le Sirius, toujours annoncé, ne relance rien.
        val watch = ComputerArming()
        watch.imported(hidden = MaresDriver.hidesAfterConnection)
        assertFalse(watch.sighting(true))
    }

    @Test
    fun aNewAppearanceCountsAgain() {
        val watch = ComputerArming()
        watch.imported(hidden = false)
        assertFalse(watch.sighting(false))
        assertTrue(watch.sighting(true))
    }

    @Test
    fun aShearwaterSeenAgainAfterAnImportIsImported() {
        // Après l'association, le Peregrine se tait ; réactivé aussitôt, avant qu'une recherche ait constaté son absence, il compte.
        val watch = ComputerArming()
        watch.imported(hidden = ShearwaterDriver.hidesAfterConnection)
        assertTrue(watch.sighting(true))
        // Détecté, puis importé : la réactivation suivante compte aussi.
        watch.imported(hidden = ShearwaterDriver.hidesAfterConnection)
        assertTrue(watch.sighting(true))
    }

    @Test
    fun aFailedConnectionIsNotRetriedWhileTheComputerStaysInRange() {
        val watch = ComputerArming()
        assertTrue(watch.sighting(true))
        // La connexion n'a pas abouti : l'ordinateur ne s'est pas tu pour autant.
        watch.imported(hidden = false)
        assertFalse(watch.sighting(true))
    }
}
