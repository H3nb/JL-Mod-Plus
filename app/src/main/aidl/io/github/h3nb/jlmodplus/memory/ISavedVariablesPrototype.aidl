package io.github.h3nb.jlmodplus.memory;

/** R&D-only main-process definition authority. Never exposes raw guest objects. */
interface ISavedVariablesPrototype {
    String list(String generation, String appPath, long appId, String mainClass);
    String save(String generation, String appPath, long appId, String mainClass,
                String label, String descriptorJson);
    String remove(String generation, String appPath, long appId, String mainClass, String recordId);
}
