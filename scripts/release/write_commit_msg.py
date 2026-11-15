msg = """ui: hand the bars, dialogs and buttons to COUI (2.21.1)

Four things were still hand-built and each showed up on device.

- top and bottom bars move into the COUI Scaffold topBar/bottomBar slots.
  The library supplies those slots with a backdrop sourced from the body;
  passing our own meant the bars sampled an empty layer and painted black.
- WindowDialog and WindowBottomSheet delegate to COUI Dialog and keep
  visible in the composition instead of returning early. Window teardown is
  async, so removing the composable and showing it again immediately left the
  old window in place and the second tap did nothing.
- dialogs take their scrim from CouiDialogColors.dimAmount (0.2 in light)
  rather than the platform default 0.6 that turned the page black.
- every button and every corner now uses CouiShape/CouiCapsuleShape with
  couiButtonFeedback and the rememberCouiSpotlight glow; RoundedCornerShape
  is gone from the UI. PanelButton and DialogActions follow the same path.
- dialog actions move into the library button panel; the eight dialogs pass
  actions instead of drawing their own row.
- the top bar is compressed to the library fixed 52dp single row.

compileReleaseKotlin + JavaWithJavac + testDebugUnitTest + assembleRelease green
tests 4/0/0; APK 16,353,474 B
versionCode 210 / versionName 2.21.1
"""
open("/tmp/commit_msg.txt","w",encoding="utf-8").write(msg)
print("提交信息已写")
