package dev.mulcor.registry;

import static dev.mulcor.registry.Registry.*;

/**
 * Vanilla entity types and attributes: dimensions ({@code EntityDimensions}), eye height, client tracking range (in
 * chunks) and the default value of every attribute the type has ({@code DefaultAttributes}).
 */
public final class EntityTypes {
    private EntityTypes() {}

    public static String name(int type) { return ENTITY_NAME[type]; }
    /** How the type is spawned on the wire in Minestom's terms (e.g. LIVING, PLAYER, BASE). */
    public static String packetType(int type) { return ENTITY_PACKET_TYPE[type]; }
    public static double width(int type) { return ENTITY_WIDTH[type]; }
    public static double height(int type) { return ENTITY_HEIGHT[type]; }
    public static double eyeHeight(int type) { return ENTITY_EYE_HEIGHT[type]; }
    /** {@code EntityType.clientTrackingRange()}, in chunks. */
    public static int trackingRange(int type) { return ENTITY_TRACKING_RANGE[type]; }

    /** Default value of {@code attribute} for this type, or NaN if the type does not have the attribute. */
    public static double attribute(int type, int attribute) { return ENTITY_ATTRIBUTES[type][attribute]; }
    public static boolean hasAttribute(int type, int attribute) { return !Double.isNaN(ENTITY_ATTRIBUTES[type][attribute]); }

    public static String attributeName(int attribute) { return ATTR_NAME[attribute]; }
    /** The attribute's base default (used when a type has it without an override). */
    public static double attributeDefault(int attribute) { return ATTR_DEFAULT[attribute]; }
    public static double attributeMin(int attribute) { return ATTR_MIN[attribute]; }
    public static double attributeMax(int attribute) { return ATTR_MAX[attribute]; }
    public static boolean attributeSynced(int attribute) { return ATTR_SYNC[attribute]; }
}
