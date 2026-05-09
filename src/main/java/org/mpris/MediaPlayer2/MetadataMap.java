package org.mpris.MediaPlayer2;

import org.freedesktop.dbus.TypeRef;
import org.freedesktop.dbus.types.Variant;

import java.util.Map;

/** TypeRef for the MPRIS Metadata property type a{sv}. */
public interface MetadataMap extends TypeRef<Map<String, Variant<?>>> {}