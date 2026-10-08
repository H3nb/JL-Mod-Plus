SELECT p.name AS process, t.name AS thread, s.name, COUNT(*) AS samples,
       ROUND(SUM(s.dur) / 1e6, 3) AS total_ms,
       ROUND(MAX(s.dur) / 1e6, 3) AS max_ms
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t USING (utid)
JOIN process p USING (upid)
WHERE p.name = 'io.github.h3nb.jlmodplus.debug' AND s.dur > 0
GROUP BY p.name, t.name, s.name
ORDER BY total_ms DESC
LIMIT 80;
