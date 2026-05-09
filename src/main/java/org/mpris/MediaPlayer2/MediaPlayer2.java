package org.mpris.MediaPlayer2;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.DBusProperty;
import org.freedesktop.dbus.interfaces.DBusInterface;

/** [https://specifications.freedesktop.org/mpris-spec/latest/Media_Player.html]
 *
 * ## Properties
 * - CanQuit		        b	Read only
 * - Fullscreen		        b	Read/Write	(optional)
 * - CanSetFullscreen		b	Read only	(optional)
 * - CanRaise		        b	Read only
 * - HasTrackList		    b	Read only
 * - Identity		        s	Read only
 * - DesktopEntry		    s	Read only	(optional)
 * - SupportedUriSchemes	as	Read only
 * - SupportedMimeTypes	    as	Read only
 * */
@DBusInterfaceName("org.mpris.MediaPlayer2")
@DBusProperty(name = "CanQuit",             type = Boolean.class,  access = DBusProperty.Access.READ)
@DBusProperty(name = "Fullscreen",          type = Boolean.class,  access = DBusProperty.Access.READ_WRITE)
@DBusProperty(name = "CanSetFullscreen",    type = Boolean.class,  access = DBusProperty.Access.READ)
@DBusProperty(name = "CanRaise",            type = Boolean.class,  access = DBusProperty.Access.READ)
@DBusProperty(name = "HasTrackList",        type = Boolean.class,  access = DBusProperty.Access.READ)
@DBusProperty(name = "Identity",            type = String.class,   access = DBusProperty.Access.READ)
@DBusProperty(name = "DesktopEntry",        type = String.class,   access = DBusProperty.Access.READ)
@DBusProperty(name = "SupportedUriSchemes", type = String[].class, access = DBusProperty.Access.READ)
@DBusProperty(name = "SupportedMimeTypes",  type = String[].class, access = DBusProperty.Access.READ)
public interface MediaPlayer2 extends DBusInterface {
    /** Brings the media player's user interface to the front using any appropriate mechanism available.
     *
     * The media player may be unable to control how its user interface is displayed, or it may not have a graphical user interface at all.
     * In this case, the CanRaise property is false and this method does nothing. */
    void Raise();

    /** Causes the media player to stop running.
     *
     * The media player may refuse to allow clients to shut it down.
     * In this case, the CanQuit property is false and this method does nothing.*/
    void Quit();
}