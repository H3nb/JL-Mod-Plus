SELECT jank_type, COUNT(*) AS frames,
       ROUND(AVG(dur) / 1e6, 3) AS mean_ms,
       ROUND(MAX(dur) / 1e6, 3) AS max_ms
FROM actual_frame_timeline_slice
JOIN process USING (upid)
WHERE process.name = 'io.github.h3nb.jlmodplus.debug' AND dur > 0
GROUP BY jank_type;
