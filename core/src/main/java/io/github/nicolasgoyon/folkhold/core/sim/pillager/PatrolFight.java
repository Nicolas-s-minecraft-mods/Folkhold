package io.github.nicolasgoyon.folkhold.core.sim.pillager;

/**
 * The result of an abstract fight between a caravan and a pillager patrol, with no entities.
 *
 * <ul>
 *   <li>The strength of a side is the sum, for each living fighter, of its health multiplied by its attack damage.
 *       The fighters of a caravan are its guards. The fighters of a patrol are its members.</li>
 *   <li>The side with the higher strength wins. When the two strengths are equal, the caravan wins.</li>
 *   <li>All fighters of the side that loses die. The side that wins loses a share of its fighters: the square of
 *       the strength of the loser divided by the strength of the winner, rounded down.</li>
 *   <li>A caravan that loses to the patrol loses a share of its cargo and of its payment.</li>
 *   <li>A caravan with no living guard has no strength: the patrol takes it, and the caravan is lost.</li>
 * </ul>
 *
 * @param caravanWins  true if the guards beat the patrol
 * @param guardDeaths  the number of guards that die
 * @param patrolDeaths the number of patrol members that die
 * @param cargoLoss    the share of the cargo and of the payment that the caravan loses, from 0 to 1
 * @param caravanLost  true if the patrol takes the caravan: the merchant dies
 */
public record PatrolFight(boolean caravanWins, int guardDeaths, int patrolDeaths, double cargoLoss,
        boolean caravanLost) {

    /** The result when the patrol has no strength: no fight. */
    public static final PatrolFight NONE = new PatrolFight(true, 0, 0, 0.0, false);

    /**
     * Calculates the result of a fight.
     *
     * @param caravanStrength the strength of the guards of the caravan, 0 or more
     * @param guards          the number of living guards of the caravan
     * @param patrolStrength  the strength of the members of the patrol, 0 or more
     * @param members         the number of living members of the patrol
     * @param cargoLossShare  the share of the cargo that a patrol takes when it wins, from 0 to 1
     * @return the result, or {@link #NONE} if the patrol has no strength
     */
    public static PatrolFight resolve(double caravanStrength, int guards, double patrolStrength, int members,
            double cargoLossShare) {
        if (patrolStrength <= 0.0 || members <= 0) {
            return NONE;
        }
        if (caravanStrength <= 0.0 || guards <= 0) {
            return new PatrolFight(false, 0, 0, 1.0, true);
        }
        if (caravanStrength >= patrolStrength) {
            return new PatrolFight(true, losses(guards, patrolStrength, caravanStrength), members, 0.0, false);
        }
        double share = Math.max(0.0, Math.min(1.0, cargoLossShare));
        return new PatrolFight(false, guards, losses(members, caravanStrength, patrolStrength), share, false);
    }

    /**
     * The strength of one fighter.
     *
     * @param health       the health of the fighter, 0 or more
     * @param attackDamage the attack damage of the fighter, 0 or more
     * @return the strength
     */
    public static double strength(double health, double attackDamage) {
        return Math.max(0.0, health) * Math.max(0.0, attackDamage);
    }

    /** The fighters of the winner that die: the square of the strength ratio, multiplied by the fighters. */
    static int losses(int fighters, double loserStrength, double winnerStrength) {
        double ratio = loserStrength / winnerStrength;
        return Math.min(fighters, (int) Math.floor(fighters * ratio * ratio + 1e-9));
    }
}
