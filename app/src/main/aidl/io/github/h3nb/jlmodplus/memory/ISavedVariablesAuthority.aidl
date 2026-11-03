package io.github.h3nb.jlmodplus.memory;

import android.os.Bundle;

/** Main-process, installed-game-scoped Saved definition authority. */
interface ISavedVariablesAuthority {
    Bundle list(String path, long appId, String entry);
    Bundle save(String path, long appId, String entry, String label, in Bundle locator);
    Bundle rename(String path, long appId, String entry, String id, String label);
    Bundle removeMany(String path, long appId, String entry, in String[] ids);
}
