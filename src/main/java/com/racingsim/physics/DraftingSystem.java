package com.racingsim.physics;

import com.jme3.math.FastMath;
import com.racingsim.track.TrackSpline;

import java.util.List;

/**
 * Aerodynamic slipstream evaluation. Run once per physics tick before aero forces are
 * computed: every car checks the cars ahead of it for a wake cone (up to {@link #WAKE_LENGTH}
 * metres behind the leader, within {@link #WAKE_HALF_WIDTH} metres laterally) and derives a
 * {@code draftFactor} in [0, {@link #MAX_DRAFT}].
 */
public final class DraftingSystem {

    /** Anything that occupies the track and can feel a slipstream. */
    public interface Participant {
        float trackS();
        float trackD();
        float speed();
        float getDraftFactor();
        void setDraftFactor(float factor);
    }

    public static final float WAKE_LENGTH = 60f;
    public static final float WAKE_HALF_WIDTH = 3.5f;
    public static final float MAX_DRAFT = 0.35f;
    /** Closest centre-to-centre gap considered (two car lengths overlap otherwise). */
    private static final float MIN_GAP = 4.5f;
    /** Contribution of additional leaders (draft "trains" give a little more tow). */
    private static final float TRAIN_BONUS = 0.25f;
    /** Time constant of the wake build-up / decay (s). */
    private static final float RESPONSE_TIME = 0.12f;

    public void update(List<? extends Participant> cars, float dt) {
        float blend = 1f - FastMath.exp(-dt / RESPONSE_TIME);
        for (Participant me : cars) {
            float best = 0f, sum = 0f;
            for (Participant lead : cars) {
                if (lead == me) continue;
                float f = wakeStrength(me, lead);
                if (f > 0) {
                    sum += f;
                    best = Math.max(best, f);
                }
            }
            float target = Math.min(MAX_DRAFT, best + TRAIN_BONUS * (sum - best));
            me.setDraftFactor(me.getDraftFactor() + (target - me.getDraftFactor()) * blend);
        }
    }

    /** Raw wake strength (0..MAX_DRAFT) felt by {@code me} behind {@code lead}. */
    public static float wakeStrength(Participant me, Participant lead) {
        float gap = TrackSpline.deltaS(me.trackS(), lead.trackS());
        if (gap <= 0f || gap > WAKE_LENGTH) return 0f;
        float lateral = Math.abs(lead.trackD() - me.trackD());
        if (lateral >= WAKE_HALF_WIDTH) return 0f;
        // a wake only exists if the leader is actually moving through the air
        float leadSpeed = lead.speed();
        if (leadSpeed < 15f) return 0f;
        float speedScale = FastMath.clamp((leadSpeed - 15f) / 30f, 0f, 1f);

        float longT = FastMath.clamp((gap - MIN_GAP) / (WAKE_LENGTH - MIN_GAP), 0f, 1f);
        float longitudinal = FastMath.pow(1f - longT, 1.3f);
        // the wake also narrows further back (cone shape)
        float halfWidth = WAKE_HALF_WIDTH * (0.75f + 0.25f * (1f - longT));
        float latT = lateral / halfWidth;
        float lateralFall = latT >= 1f ? 0f : 1f - latT * latT;
        return MAX_DRAFT * longitudinal * lateralFall * speedScale;
    }
}
