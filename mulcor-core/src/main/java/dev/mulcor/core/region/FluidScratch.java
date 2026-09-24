package dev.mulcor.core.region;

/** Per-region scratch for {@link Fluids}: the directions and fluid states {@code FlowingFluid.getSpread} returns. */
final class FluidScratch {
    final int[] spreadDir = new int[4];
    final int[] spreadFluid = new int[4];
}
