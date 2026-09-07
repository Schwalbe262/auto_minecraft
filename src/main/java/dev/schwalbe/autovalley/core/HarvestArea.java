package dev.schwalbe.autovalley.core;

import java.util.ArrayList;
import java.util.List;

/** Geometry of the supported native range, including its upper-cell fallback. */
public final class HarvestArea {
    private HarvestArea() { }
    public static int halfSpan(int nativeRange,boolean areaEnabled) {
        return nativeRange<1 || nativeRange>5 ? -1 : areaEnabled ? nativeRange-1 : 0;
    }
    public static List<Pos> cells(Pos target,int halfSpan) {
        if (target==null || halfSpan<0 || halfSpan>4) throw new IllegalArgumentException("Unknown harvest geometry");
        List<Pos> cells=new ArrayList<>();
        for (int dx=-halfSpan;dx<=halfSpan;dx++) for (int dz=-halfSpan;dz<=halfSpan;dz++)
            for (int dy=0;dy<=1;dy++) cells.add(target.offset(dx,dy,dz));
        return List.copyOf(cells);
    }
}
