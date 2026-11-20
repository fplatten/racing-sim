package com.racingsim.ui;

import com.racingsim.track.LapTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks which rows of the race order to show when the field is bigger than the panel:
 * the leaders, a gap marker ({@code null}) and a window around the player, so the player's
 * row is always visible.
 */
final class StandingsView {

    private StandingsView() { }

    static List<LapTracker.Entry> rows(List<LapTracker.Entry> order, LapTracker.Entry me, int maxRows) {
        List<LapTracker.Entry> out = new ArrayList<>();
        int n = order.size();
        int mine = order.indexOf(me);
        if (n <= maxRows || mine < maxRows - 1) {
            for (int i = 0; i < Math.min(n, maxRows); i++) out.add(order.get(i));
            if (n > maxRows && mine >= maxRows) out.set(maxRows - 1, me);
            return out;
        }
        int leaders = 3;
        int window = maxRows - leaders - 1;
        int from = Math.max(leaders, Math.min(mine - window / 2, n - window));
        for (int i = 0; i < leaders; i++) out.add(order.get(i));
        out.add(null);                                       // "..." gap marker
        for (int i = from; i < from + window; i++) out.add(order.get(i));
        return out;
    }
}
