/**
 * Regions: the unit of parallel simulation.
 *
 * <h2>Execution model</h2>
 * The world's cells are partitioned into regions ({@link dev.mulcor.core.grid.Partition}). Each tick (epoch):
 * <ol>
 *   <li>Every active region is ticked exactly once by some worker thread. A worker claims the region via the
 *       region's CAS state word ({@code SCHEDULED → RUNNING}), so two threads can never run one region.
 *       A region mutates only its own cells and its own entities.</li>
 *   <li>When the last region finishes, one thread runs the commit phase alone: it reclaims block sections,
 *       rebalances regions, forwards messages stranded in retired region slots, and orders the next epoch's
 *       schedule.</li>
 * </ol>
 *
 * <h2>Cross-region consistency invariant</h2>
 * <b>Every cross-region effect is delivered exactly one epoch after it is sent</b>, never in the same epoch
 * and never later. This covers entity transfers, block breaks and places, explosions, redstone signals and
 * inventory operations.
 * <ul>
 *   <li>Each region has two inboxes, selected by epoch parity. During epoch {@code e}, senders write to
 *       {@code inbox[e & 1]}. The receiver drains only {@code inbox[(e - 1) & 1]}, which nobody writes during
 *       {@code e}. It drains that inbox completely, and receives everything sent in {@code e - 1}.</li>
 *   <li>Cross-region redstone and physics are therefore <b>eventually consistent with a one-epoch delay per
 *       region boundary crossed</b>. A redstone line crossing k boundaries settles k epochs later than the
 *       same line inside one region.</li>
 *   <li>Cross-region block reads ({@link dev.mulcor.memory.BlockStorage#getShared}) see a value from the
 *       current or previous epoch.</li>
 *   <li>If a target inbox is full, the sender keeps the message in its private overflow ring and retries next
 *       epoch; delivery is then one epoch after the successful send. Entity transfers are cancelled instead
 *       and the entity stays with the sender, which retries its move next tick.</li>
 * </ul>
 *
 * <h2>Zero-duplication</h2>
 * <ul>
 *   <li><b>Entities:</b> an entity's ownership word ({@link dev.mulcor.memory.Ownership}) moves
 *       {@code OWNED(src) → IN_TRANSIT(dst, e+1) → OWNED(dst)}, and each step is a single CAS. A duplicated or
 *       replayed transfer record fails its CAS and is discarded, so an entity is never live in two regions.</li>
 *   <li><b>Items and blocks:</b> each chest and block has exactly one owning region, and every mutation from
 *       anywhere is funnelled into that owner as a message. So two players clicking the same chest slot or
 *       breaking the same block in the same microsecond are serialized, and only the first succeeds.</li>
 * </ul>
 */
package dev.mulcor.core.region;
