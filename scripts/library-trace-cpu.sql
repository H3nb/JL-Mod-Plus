SELECT p.name AS process, t.name AS thread,
       ROUND(SUM(s.dur) / 1e6, 3) AS running_ms
FROM sched s
JOIN thread t USING (utid)
JOIN process p USING (upid)
WHERE p.name = 'io.github.h3nb.jlmodplus.debug' AND s.dur > 0
GROUP BY p.name, t.name
ORDER BY running_ms DESC;
