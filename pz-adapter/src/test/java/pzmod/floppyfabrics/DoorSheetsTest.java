package pzmod.floppyfabrics;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import zombie.iso.IsoDirections;

/** Sheets on doors (DECISIONS D95): the 2D placement table ported from IsoDoor.prerender2x* / postrender2x*. */
class DoorSheetsTest {

    @Test
    void closedDoorsHangTheSheetOnTheSideItWasHungFrom() {
        // north door, closed: leaf on the north edge. Inside = the door square's side (south of the edge): curtainN on
        // the door square; outside = curtainS on the square to the north
        assertEquals(new DoorSheets.Placement(DoorSheets.N, 0, 0, -20f, -20f), DoorSheets.placement(IsoDirections.N, true, false, true));
        assertEquals(new DoorSheets.Placement(DoorSheets.S, 0, -1, -3f, -30f), DoorSheets.placement(IsoDirections.N, true, false, false));
        // west door, closed: curtainW on the door square, curtainE on the square to the west
        assertEquals(new DoorSheets.Placement(DoorSheets.W, 0, 0, -5f, -20f), DoorSheets.placement(IsoDirections.W, false, false, true));
        assertEquals(new DoorSheets.Placement(DoorSheets.E, -1, 0, -26f, -31f), DoorSheets.placement(IsoDirections.W, false, false, false));
    }

    @Test
    void everyStateVanillaCanReachHasAPlacement() {
        for (boolean inside : new boolean[]{true, false}) {
            // closed: the leaf's edge is the door's own
            assertNotNull(DoorSheets.placement(IsoDirections.N, true, false, inside));
            assertNotNull(DoorSheets.placement(IsoDirections.W, false, false, inside));
            // open, plain doors: a north door's leaf turns onto the west edge, a west door's onto the north edge
            assertNotNull(DoorSheets.placement(IsoDirections.W, true, true, inside));
            assertNotNull(DoorSheets.placement(IsoDirections.N, false, true, inside));
            // open doors whose open sprite is attachedE / attachedS
            assertNotNull(DoorSheets.placement(IsoDirections.E, true, true, inside));
            assertNotNull(DoorSheets.placement(IsoDirections.S, false, true, inside));
        }
        // a combination getSpriteEdge never returns (a closed west door on its north edge) draws nothing
        assertNull(DoorSheets.placement(IsoDirections.N, false, false, true));
    }

    @Test
    void sheetsOnTheCamerasSideAreDrawnAfterTheDoor() {
        // D96: vanilla's postrender2x* draws N/W sheets (in front of the leaf), prerender2x* S/E (behind it)
        for (boolean north : new boolean[]{true, false}) {
            for (boolean open : new boolean[]{true, false}) {
                for (boolean inside : new boolean[]{true, false}) {
                    for (IsoDirections edge : new IsoDirections[]{IsoDirections.N, IsoDirections.S, IsoDirections.W, IsoDirections.E}) {
                        DoorSheets.Placement p = DoorSheets.placement(edge, north, open, inside);
                        if (p == null) continue;
                        boolean front = p.sprite().equals(DoorSheets.N) || p.sprite().equals(DoorSheets.W);
                        assertEquals(front, p.afterDoor(), p.toString());
                    }
                }
            }
        }
        // the reported case: a closed north door, sheet hung from the outside of a building whose south wall it is in
        // (the door square is outdoors, so its side is "inside"): drawn on the door square, after the door
        assertTrue(DoorSheets.placement(IsoDirections.N, true, false, true).afterDoor());
        assertFalse(DoorSheets.placement(IsoDirections.N, true, false, false).afterDoor());
    }

    @Test
    void theSheetSpritesAreTheHangingWindowSheetsTheirFacingFollows() {
        // the sprite's tile type decides the cloth's facing (Curtains.facingFor): N/W hang on the edge of the square they
        // are drawn on, S/E on the far edge of theirs — always the leaf's line
        for (DoorSheets.Placement p : new DoorSheets.Placement[]{
                DoorSheets.placement(IsoDirections.N, true, false, false),
                DoorSheets.placement(IsoDirections.W, false, false, false)}) {
            assertTrue(p.sprite().equals(DoorSheets.S) || p.sprite().equals(DoorSheets.E), "far-edge sprite " + p);
            assertTrue(p.dx() == -1 || p.dy() == -1, "drawn on the neighbouring square " + p);
        }
    }
}
