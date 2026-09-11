from pathlib import Path

root = Path('.')

# Add a CTA Overlay entry point without changing the existing OUTRO flow.
p = root / 'app/src/main/java/com/thedailyflare/reel/MainActivity.kt'
s = p.read_text()
anchor = 'root.addView(twoColumnRow("" to button("IMAGE") { pickImages() }, "" to button("OUTRO") { pickImage(101) }), lp())'
if 'CtaOverlayActivity::class.java' not in s:
    if anchor not in s:
        raise SystemExit('MainActivity UI anchor not found')
    p.write_text(s.replace(anchor, anchor + '\n        root.addView(button("CTA OVERLAY") { startActivity(Intent(this, CtaOverlayActivity::class.java)) }, lp())', 1))

# Keep the CTA timeline tied to the complete visual-preview progress.
p = root / 'app/src/main/java/com/thedailyflare/reel/MainActivity.kt'
s = p.read_text()
old = 'preview.visualProgress = (progress * images.size - segment).coerceIn(0f, 1f); preview.textPreviewProgress = progress;'
new = 'preview.visualProgress = (progress * images.size - segment).coerceIn(0f, 1f); preview.timelineProgress = progress; preview.textPreviewProgress = progress;'
if 'preview.timelineProgress = progress' not in s:
    if old not in s:
        raise SystemExit('MainActivity visual preview progress anchor not found')
    p.write_text(s.replace(old, new, 1))

# Keep export integration. The existing final 3-second OUTRO branch remains untouched.
p = root / 'app/src/main/java/com/thedailyflare/reel/ReelEncoder.kt'
s = p.read_text()
marker = 'val settledMask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)'
if 'val ctaOverlayRenderer = CtaOverlayRenderer' not in s:
    if marker not in s:
        raise SystemExit('ReelEncoder bitmap marker not found')
    s = s.replace(marker, marker + '\n        val ctaOverlayRenderer = CtaOverlayRenderer(context, CtaOverlayStore.load(context))', 1)
text_anchor = '''drawAnimatedText(canvas, title, headlines, width, height, frame, titleDelayFrames,
                            headlineWordCounts, segmentFrames, textEffect, textEffectIntensity, textRevealMode,
                            animatedLayer, settledMask)'''
if 'ctaOverlayRenderer.draw(canvas' not in s:
    if text_anchor not in s:
        raise SystemExit('ReelEncoder text render anchor not found')
    s = s.replace(text_anchor, text_anchor + '\n                        ctaOverlayRenderer.draw(canvas, frame.toLong() * 1000L / fps.toLong(), width, height)', 1)
if 'ctaOverlayRenderer.release()' not in s:
    old = 'finally { try { input?.release() } catch (_: Exception) {}; try { surface?.release() } catch (_: Exception) {}; if (started) try { muxer.stop() } catch (_: Exception) {}; muxer.release(); try { codec.stop() } catch (_: Exception) {}; codec.release() }'
    new = 'finally { try { input?.release() } catch (_: Exception) {}; try { surface?.release() } catch (_: Exception) {}; if (started) try { muxer.stop() } catch (_: Exception) {}; muxer.release(); try { codec.stop() } catch (_: Exception) {}; codec.release(); ctaOverlayRenderer.release() }'
    if old not in s:
        raise SystemExit('ReelEncoder finally anchor not found')
    s = s.replace(old, new, 1)
p.write_text(s)

# Keep the debug activity registration self-contained.
manifest = root / 'app/src/debug/AndroidManifest.xml'
manifest.parent.mkdir(parents=True, exist_ok=True)
manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <activity android:name=".CtaOverlayActivity" android:exported="false" />
    </application>
</manifest>
''')

# ReelPreviewView and CtaOverlayRenderer are committed source files now.
# Do not overwrite them here: this keeps preview/editing fixes intact on every build.
