# UX rules

Rules for every screen in Tender, on iPhone, iPad and Mac. They come from feedback on earlier
builds; when a new screen breaks one, it is a bug.

## Follow Frigate

- When Frigate's web UI does something a certain way, the app does it the same way: the same
  names (Live, Review, Explore), the same flows, the same capitalisation of labels.
- Features that do not work reliably are left out, not shipped half-working (as with 4× playback).
- Hide what does not apply: no plate search without plates, no talk or PTZ on a camera that is
  offline, no user management when Frigate runs without authentication.

## Waiting on the server

- Every change that waits for Frigate shows a spinner in the control that started it (the
  button, row, chip or tile) and disables that control until Frigate answers. A spinner somewhere
  else on screen is not enough. Only when the control is gone (a long-press menu) does the
  progress pill at the top show it.
- What changed shows at once and stays: a deleted item disappears and does not come back on
  the next refresh; badges and counters change together with the list.
- Problems show at the top of the screen they belong to (for example a banner in Live), only
  while something is wrong. No messages in a corner; nothing when all is well.
- Numbers that keep changing (MB/s, timers) use monospaced digits, so the text does not jump.

## Dialogs and sheets

- A delete or remove confirmation always has a Cancel button next to the red action. Use
  `.alert` with `Button("Cancel", role: .cancel)`, as the rest of the app does.
- Destructive items in menus (Delete, Undo, Remove) have a red icon and red text
  (`DestructiveMenuLabel`).
- Every sheet can be closed: Cancel works, and other buttons that finish the task close it too.
- On the Mac a sheet is as large as its content (`fittedSheet`), not a stretched iOS dialog.
  Asking for one line of text uses `textPrompt`, which draws a small sheet on the Mac.
- No stray padding or empty space under a sheet's content.

## Forms

- On the Mac, Tab and Shift-Tab move to the next and previous field
  (`TabBetweenFields.swift`). Return confirms, Esc cancels.
- The field being typed in shows an accent edge and label (`FocusRow`).
- On/off settings are switches, never radio buttons or checkboxes, with the label on the left
  and the switch on the right (`RowSwitchStyle`).
- A choice from a short list is a row with an up/down chevron that opens a menu. On the Mac
  the menu holds plain buttons with a checkmark; a `Picker` inside a `Menu` becomes a submenu
  there.
- Password fields can show the password.
- Input is forgiving: a server address without `http://` or a port still works, and a wrong
  address fails quickly instead of hanging.
- Passwords in addresses and logs are hidden (`••••`), also while editing; the original comes
  back when the hidden part is left as it is.

## Buttons and consistency

- The same kind of action looks the same everywhere: the same style, height and corner radius.
  Buttons side by side are equally wide and equally tall.
- Pushed screens use `ScreenTitle(showsBack: true)`, so the back button looks and sits the same
  on every screen. Video screens with their own bar use `BackButtonLabel`, the same arrow. On iOS 26 and later
  the back button and the top bar's icon buttons (`HeaderIconLabel`) are round glass buttons, like
  the system's Done and Cancel. Text buttons in a top bar (Mark all reviewed, Select, Save) are
  glass capsules (`HeaderTextLabel`).
- Prefer an icon over a word where the icon is clear (sorting, objects in a picture). Object
  icons sit at the top left of a picture, everywhere.
- Text in bars, buttons and chips never wraps: short labels, `lineLimit(1)`, `fixedSize()`.
  Check it at iPhone width in Dutch, which is longer than English.
- Spacing is the same everywhere: the same space above a title, between sections and around
  cards. Videos and pictures have rounded corners, like cards.
- Lists that scroll sideways run to the edge of the screen, not to the edge of the padding.
- With more than one server, everything that belongs to a server says which one.
- A switched-off state (a camera, detection, recording) is clearly visible, not only in a menu.

## iPhone, iPad and Mac

- iPhone: no sidebar. Side lists (such as the events next to a video) open as an overlay from
  a button, not by default.
- iPad and Mac: use the room. Use grids and side panels instead of one stretched column, and
  give every page the same maximum readable width (`readableWidth`).
- Mac: controls are large enough to click. Video controls appear when the pointer moves over
  the video (on touch: when you tap it). Full screen fills the screen, not only the
  window. Timelines scroll with two fingers on the trackpad.
- What should stay put stays put: when a list under a video scrolls, the video does not move.
- A menu or panel the user opened does not hide by itself.
- Nothing flickers: no menus that redraw while a video plays, no header that flashes when you
  switch pages.
- Full screen on an iPhone in portrait turns to landscape; leaving full screen returns to portrait.

## Video

- Playback starts by itself; a paused video looks paused, not stuck or loading.
- Tapping a moment or object in a timeline jumps exactly there, and scrubbing shows the same
  moment the video then plays.

## Text

- Every string is written in English in the code and translated in `Localizable.xcstrings`
  (Dutch for now). Use Frigate's words for Frigate's features.

## Before building something new

- Show mockups first for new screens and bigger changes, for iPhone (portrait and landscape),
  iPad and Mac where they differ.
- Test in the iOS Simulator and check the screen on iPhone width, iPad and Mac.
