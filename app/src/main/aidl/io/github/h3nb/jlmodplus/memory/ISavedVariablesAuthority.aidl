package io.github.h3nb.jlmodplus.memory;

import android.os.Bundle;

/** Main-process, installed-game-scoped Saved definition authority. */
interface ISavedVariablesAuthority {
    Bundle list(String path, long appId, String entry);
    Bundle save(String path, long appId, String entry, String label, in Bundle locator);
    /** One persistent Bookmark, with an optional portable rooted path. */
    Bundle bookmark(String path, long appId, String entry, String label, in Bundle locator);
    Bundle relinkBookmark(String path, long appId, String entry, String id, in Bundle locator);
    Bundle rename(String path, long appId, String entry, String id, String label);
    Bundle removeMany(String path, long appId, String entry, in String[] ids);
    /** JSON-only scripts live in a separate AtomicFile under this installed game. */
    Bundle listRecipes(String path, long appId, String entry);
    Bundle putRecipe(String path, long appId, String entry, String document, String replacingId);
    Bundle deleteRecipe(String path, long appId, String entry, String recipeId);
}
