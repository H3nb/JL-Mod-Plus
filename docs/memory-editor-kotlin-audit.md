# Memory Editor: audit Kotlin

Tanggal audit: 2026-09-12

## Kesimpulan

Memory Editor sudah memakai Kotlin pada lapisan app-owned UI dan state:

- `MemoryEditorRuntimeCompose.kt`
- `MemoryEditorController.kt`
- `MemoryEditorModels.kt`
- `MemoryInputModel.kt` dan `MemorySearchExpression.kt`
- `MemoryEditorBubbleController.kt` serta activity Compose terkait

Refaktor yang aman dan bernilai nyata dilakukan pada dua helper stateless:

- `ManagedJavaMemoryIds.kt` mempertahankan codec logical ID dengan `Long`/`Int` primitif.
  `@JvmStatic` dan `@JvmField` menjaga pemanggilan static dari engine, service, dan test Java.
- `MemoryManagedServicePolicy.kt` mempertahankan aturan penerimaan Binder reply dan scheduler
  freeze tanpa allocation atau collection pipeline.

Kedua helper tidak menambah dependency, wrapper, atau lapisan arsitektur baru.

## Kode yang tetap Java

- `ManagedJavaMemoryEngine.java`: jalur traversal graph, reflection, buffer primitif, dan loop
  pencarian yang sensitif terhadap allocation/boxing. Migrasi menyeluruh ke Kotlin tidak memberi
  keuntungan terukur dan berisiko menambah overhead atau mengubah perilaku compatibility layer.
- `ManagedJavaValue.java`: codec numerik/reflection dengan banyak primitive switch; Java saat ini
  adalah implementasi yang lebih langsung dan stabil.
- `MemoryEngineService.java`, `MemoryTargetBridgeService.java`, dan `MemoryRuntimeSession.java`:
  batas Android Binder/AIDL dan lifecycle service. Java menjaga ABI generated Stub dan menghindari
  perubahan interop yang tidak diperlukan.
- `MemoryEngineContract.java`: kontrak IPC bersama Java/Kotlin/AIDL; field static dan konstanta
  sengaja dipertahankan sebagai Java surface.
- `ManagedAutoKnownQuery.java`: parser/query kecil yang dipanggil engine Java dan menggunakan
  `BigInteger`; belum ada manfaat efisiensi yang membenarkan perubahan interop.

## Prinsip lanjutan

Refaktor berikutnya hanya layak dilakukan jika ada kebutuhan perilaku atau benchmark yang jelas.
Jangan mengubah loop engine menjadi collection pipeline/`Sequence`, karena jalur tersebut hot dan
kemungkinan menghasilkan allocation/boxing. Kotlin baru sebaiknya memakai primitive types dan
mempertahankan satu pemilik state untuk setiap alur editor.
