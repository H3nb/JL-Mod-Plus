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
SELECT p.name AS process, t.name AS thread,
       ROUND(SUM(s.dur) / 1e6, 3) AS running_ms
FROM sched s
JOIN thread t USING (utid)
JOIN process p USING (upid)
WHERE p.upid IN (SELECT upid FROM app_process) AND s.dur > 0
GROUP BY p.name, t.name
ORDER BY running_ms DESC;
