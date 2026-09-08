package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.LoggingJumpEdge;

/** Same measured one-pulse physics; never grants or consults logging work permissions. */
public final class StepUpController extends LoggingJumpController {
    public StepUpController(LoggingJumpEdge edge) { super(edge,false); }
    @Override protected String actionLabel() { return "한 칸 오르기"; }
}
