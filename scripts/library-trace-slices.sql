-- Cold-start process snapshots can retain the inherited zygote name.
-- Require the exact MainActivity layer and main-thread identity for that fallback.
WITH app_process AS (
    SELECT upid FROM process WHERE name = 'io.github.h3nb.jlmodplus.debug'
    UNION
    SELECT t.upid FROM thread t
    WHERE t.is_main_thread = 1 AND t.name = 'jlmodplus.debug'
      AND EXISTS (
          SELECT 1 FROM actual_frame_timeline_slice f
          WHERE f.upid = t.upid
            AND f.layer_name GLOB 'TX - io.github.h3nb.jlmodplus.debug/io.github.h3nb.jlmodplus.MainActivity#*'
      )
)
SELECT p.name AS process, t.name AS thread, s.name, COUNT(*) AS samples,
       ROUND(SUM(s.dur) / 1e6, 3) AS total_ms,
       ROUND(MAX(s.dur) / 1e6, 3) AS max_ms
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t USING (utid)
JOIN process p USING (upid)
WHERE p.upid IN (SELECT upid FROM app_process) AND s.dur > 0
GROUP BY p.name, t.name, s.name
ORDER BY total_ms DESC
LIMIT 80;
