# Generated file-video fixtures

These synthetic flashes and tones contain no recordings or commercial assets.
Only the instrumentation APK packages these fixtures. Host FFmpeg generates
them independently of the production Android decoder.

`markers.mp4` has a four-second MPEG-4 Part 2 track, 176x144 at 15 fps, and
AAC audio. Each second begins with a white flash and a 1 kHz beep. `pure.mp4`
has no audio track. `offset.mp4` keeps four seconds of video with two seconds
of audio offset by 500 ms; its first AAC packet includes encoder priming.
`short-video.mp4` has two seconds of video and four seconds of audio.
`audio-only.mp4` exercises audio-only ISO-BMFF routing. `h263.3gp` exercises
H.263 capability selection, and `avc.mp4` exercises H.264 when available.
`legacy-level.mp4` mirrors the corpus's 240x320/10 fps MPEG-4 SP stream with
a level-0 header (profile/level byte `0x08`), testing selection by actual size
and decoding throughput. FFmpeg's numeric `-level:v 8` emits this byte;
`-level:v 0` emits the reserved byte `0x00` instead.

`gapped.mp4` retains a three-second video and AAC container timestamps while
removing audio frames between one and two seconds. The second 1 kHz tone must
resume near two seconds, with source-owned silence in between. `gapped-48k.mp4`
uses 48 kHz input to exercise resampler delay/drain at the discontinuity; output
remains 44.1 kHz. Native tests seek before, within and after the gap and measure
tone energy against the original timeline, including final duration.

Run from this directory with host FFmpeg (checked-in outputs generated with
`N-123778-g3b55818764-win64-gpl`):

```powershell
ffmpeg -y -f lavfi -i "color=c=black:s=176x144:r=15:d=4,drawbox=x=0:y=0:w=iw:h=ih:color=white:t=fill:enable='lt(mod(t,1),0.07)'" -f lavfi -i "aevalsrc=if(lt(mod(t\,1)\,0.04)\,0.4*sin(2*PI*1000*t)\,0):s=44100:d=4" -c:v mpeg4 -bf 0 -g 15 -q:v 5 -c:a aac -b:a 48k markers.mp4
ffmpeg -y -i markers.mp4 -map 0:v -an -c copy pure.mp4
ffmpeg -y -i markers.mp4 -itsoffset 0.5 -t 2 -i markers.mp4 -map 0:v -map 1:a -c copy offset.mp4
ffmpeg -y -t 2 -i markers.mp4 -i markers.mp4 -map 0:v -map 1:a -c copy short-video.mp4
ffmpeg -y -i markers.mp4 -map 0:a -vn -c copy audio-only.mp4
ffmpeg -y -i markers.mp4 -c:v h263 -g 15 -q:v 5 -c:a copy h263.3gp
ffmpeg -y -i markers.mp4 -c:v libx264 -profile:v baseline -bf 0 -g 15 -c:a copy avc.mp4
ffmpeg -y -i markers.mp4 -vf scale=240:320,setsar=1,fps=10 -c:v mpeg4 -level:v 8 -bf 0 -g 10 -q:v 5 -c:a copy -t 3 legacy-level.mp4
ffmpeg -y -f lavfi -i "color=c=black:s=176x144:r=15:d=3" -f lavfi -i "sine=frequency=1000:sample_rate=44100:duration=3" -af "aselect='not(between(t,1,2))'" -c:v mpeg4 -bf 0 -g 15 -q:v 5 -c:a aac -b:a 48k gapped.mp4
ffmpeg -y -f lavfi -i "color=c=black:s=176x144:r=15:d=3" -f lavfi -i "sine=frequency=1000:sample_rate=48000:duration=3" -af "aselect='not(between(t,1,2))'" -c:v mpeg4 -bf 0 -g 15 -q:v 5 -c:a aac -b:a 48k gapped-48k.mp4
```

Frame-rendered timestamps compared with the mapped PCM presentation clock
measure scheduling skew. They do not establish acoustic or panel latency.
