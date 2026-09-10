package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Display-only custom facility rows; legacy kegs retain their existing POI/group controls. */
public final class WineFacilityListView {
    private WineFacilityListView() { }
    public static List<WineProductionLine> snapshot(Profile profile) {
        return WineProductionRules.lines(profile).stream()
            .filter(line->!WineProductionRules.LEGACY_ID.equals(line.id())).toList();
    }
    /** A stale row or a different world's profile cannot select or edit another production line. */
    public static boolean current(Profile owner,Profile current,WineProductionLine line) {
        return owner!=null && owner==current && line!=null && line.equals(WineProductionRules.line(current,line.id()));
    }
    /** Selection follows the stable ID, never a neighbouring row after reorder/removal. */
    public static int indexOf(List<WineProductionLine> lines,String id) {
        if(lines==null || id==null)return -1;
        for(int i=0;i<lines.size();i++)if(lines.get(i)!=null && id.equals(lines.get(i).id()))return i;
        return -1;
    }
}
