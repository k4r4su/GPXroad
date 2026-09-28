package com.olivier.gpxroad.shared.roadbook

import kotlin.math.max
import kotlin.math.min

/**
 * Géométrie du pictogramme de rond-point (it33, retour terrain : « il faudrait dessiner le rond-point
 * pour indiquer clairement la sortie, l'image générique n'est pas du tout claire »). Angles en degrés,
 * 0 = haut (tout droit), sens HORAIRE positif ; l'entrée est toujours en bas (180°). Circulation à
 * droite (France) : on tourne dans le sens INVERSE des aiguilles d'une montre, par la droite.
 *
 * @property exitAngleDegrees position de la sortie prise = virage net réel de la trace (droite 90° →
 *   à droite ; tout droit → en haut ; gauche 90° → à gauche ; demi-tour → juste à gauche de l'entrée).
 * @property pathSweepDegrees longueur de l'arc parcouru, de l'entrée à la sortie (toujours > 0).
 * @property intermediateExitAngles sorties passées avant la bonne (quand leur nombre est connu),
 *   réparties régulièrement sur le trajet.
 */
data class RoundaboutPictogramLayout(
    val exitAngleDegrees: Double,
    val pathSweepDegrees: Double,
    val intermediateExitAngles: List<Double>,
)

object RoundaboutPictogram {
    const val ENTRY_ANGLE_DEGREES = 180.0

    /** Demi-tour : la sortie est dessinée juste à gauche de l'entrée, jamais dessus. */
    private const val U_TURN_EXIT_DEGREES = -160.0

    /** Au-delà, un rang de sortie n'est plus lisible sur un pictogramme (détection douteuse). */
    private const val MAX_DRAWN_EXITS = 8

    /**
     * @param signedTurnDegrees virage net de la trace à travers le rond-point (droite > 0).
     * @param exitCount rang de la sortie prise (Valhalla), `null` si inconnu.
     */
    fun layout(signedTurnDegrees: Double, exitCount: Int?): RoundaboutPictogramLayout {
        val exit = if (signedTurnDegrees > -U_TURN_EXIT_DEGREES || signedTurnDegrees <= U_TURN_EXIT_DEGREES) {
            U_TURN_EXIT_DEGREES
        } else {
            signedTurnDegrees
        }
        val sweep = ENTRY_ANGLE_DEGREES - exit
        val rank = min(max(exitCount ?: 1, 1), MAX_DRAWN_EXITS)
        val intermediates = (1 until rank).map { ENTRY_ANGLE_DEGREES - sweep * it / rank }
        return RoundaboutPictogramLayout(exit, sweep, intermediates)
    }
}
