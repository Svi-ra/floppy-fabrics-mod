package pzmod.floppyfabrics;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.iso.objects.IsoWindow;

/**
 * {@code public void IsoWindow.syncIsoObjectReceive(ByteBufferReader, UdpConnection)} — the only overload in the 42.21.0
 * jar (verified by reflection; older decompiles show a 1-argument form): window state (open, destroyed, glass removed)
 * arriving from the server in multiplayer. Re-classifies its curtains (D66).
 */
@Patch(className = "zombie.iso.objects.IsoWindow", methodName = "syncIsoObjectReceive")
public final class Patch_WindowSync {
    private Patch_WindowSync() {}

    @Patch.OnExit
    public static void exit(@Patch.This IsoWindow self, @Patch.Argument(0) ByteBufferReader bb,
                            @Patch.Argument(1) UdpConnection source) {
        Hooks.onWindowChanged(self);
    }
}
