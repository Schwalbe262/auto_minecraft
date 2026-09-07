package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NavigationFailureMessageTest {
    private static class Nav implements Navigation {
        String detail;
        Nav(String detail) { this.detail=detail; }
        public Result moveTo(Pos target,double reach,Context context) { return Result.BLOCKED; }
        public String failureReason() { return detail; }
        public void reset() { detail=""; }
    }
    @Test void capturesDetailedFailureBeforeReset() {
        Nav nav=new Nav("verified approach has no line of sight");
        Context c=new Context(null,null,nav,new Profile());
        String result=ModuleSupport.navigationFailure(c,"Tomato source cannot be reached");
        nav.reset();
        assertEquals("Tomato source cannot be reached: verified approach has no line of sight",result);
        assertEquals("",nav.failureReason());
    }
    @Test void adaptersWithoutDetailsKeepTheirSummary() {
        for(String detail:new String[]{null,"","   "}) {
            assertEquals("Source blocked",ModuleSupport.navigationFailure(new Context(null,null,new Nav(detail),new Profile()),"Source blocked"));
        }
    }
    @Test void legacyAdaptersNeedNoNewImplementation() {
        Navigation nav=new Navigation() {
            public Result moveTo(Pos target,double reach,Context context){return Result.BLOCKED;}
            public void reset(){}
        };
        assertEquals("",nav.failureReason());
    }
}
