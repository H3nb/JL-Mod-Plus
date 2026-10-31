/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.shell.transform;

import com.android.dex.ClassDef;
import com.android.dex.Dex;
import com.android.dex.FieldId;
import com.android.dex.MethodId;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Read-only fallback for installed DEX payloads that predate the fingerprint.
 * Only conclusive contradictions count: converted classes need not use clock APIs
 * or own any managed static roots.
 */
final class MidletDexInspection {
	private MidletDexInspection() {
	}

	static boolean hasNoContradictoryLegacyEvidence(File payload) throws IOException {
		Dex dex = new Dex(payload);
		List<String> types = dex.typeNames();
		List<String> names = dex.strings();
		boolean managedTailReferenced = false;
		for (MethodId method : dex.methodIds()) {
			String owner = types.get(method.getDeclaringClassIndex());
			String name = names.get(method.getNameIndex());
			if ("Ljavax/microedition/shell/MemoryDiscoveryBridge;".equals(owner)
					&& "tailSeen".equals(name)) {
				managedTailReferenced = true;
			}
			if (("Ljava/lang/System;".equals(owner)
						&& ("currentTimeMillis".equals(name) || "nanoTime".equals(name)))
					|| ("Ljava/lang/Thread;".equals(owner)
						&& ("sleep".equals(name) || "yield".equals(name)))) {
				return false;
			}
		}
		if (managedTailReferenced) return true;
		List<FieldId> fields = dex.fieldIds();
		for (ClassDef clazz : dex.classDefs()) {
			if (clazz.getClassDataOffset() == 0) continue;
			Dex.Section section = dex.open(clazz.getClassDataOffset());
			int staticCount = section.readUleb128();
			section.readUleb128(); // instance fields
			section.readUleb128(); // direct methods
			section.readUleb128(); // virtual methods
			if (staticCount > fields.size()) return false;
			int index = 0;
			for (int i = 0; i < staticCount; i++) {
				int delta = section.readUleb128();
				int flags = section.readUleb128();
				if (delta < 0 || index > Integer.MAX_VALUE - delta) return false;
				index += delta;
				if (index >= fields.size()) return false;
				String type = types.get(fields.get(index).getTypeIndex());
				// Constant String fields are encoded as static-final and need no class tail.
				if ((flags & 0x10) == 0 && (type.startsWith("L") || type.startsWith("["))) return false;
			}
		}
		// No contradiction is not proof of a matching converter. Preserve legacy behavior.
		return true;
	}
}
