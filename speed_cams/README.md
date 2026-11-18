# Speed Cams

Warns about speed cameras on the road ahead and adds a full-screen map to the iDrive screen.

## What it does

- **Camera data**: downloads every `highway=speed_camera` node and `type=enforcement` relation
  from OpenStreetMap for a 60 km radius around the car, cached on the phone for a week.
- **Road ahead**: downloads the roads within 1.5 km, matches the car to its road and direction,
  and follows that road through each junction the straightest way.
  A camera alerts when it is within the alert distance (0.3 mi by default) along that path.
  Without road data it falls back to a narrow corridor straight ahead.
- **Alerts**, once per camera per approach:
  - a chime and a spoken warning ("Speed camera ahead, 0.3 miles. Limit 30") as navigation audio,
    which ducks music and plays through the car when the phone's audio is connected
  - a two-line iDrive popup over whatever screen is showing, closing after 8 seconds
  - a heads-up phone notification that removes itself
  - an urgent chime if you are over the camera's limit inside the alert zone
- **Full-screen map** in the car (iDrive menu, Navigation section): heading-up moving map,
  the predicted road ahead in blue, cameras as coloured markers, a warning banner with a countdown,
  and a Waze-style speed box that turns red above the limit, next to a speed limit sign
  taken from OpenStreetMap `maxspeed` (UK national limits included).
- **Speed source**: phone GPS at 1 Hz, falling back to the car's own GPS from the Car Data Service
  when the phone has no recent fix.

## Setup

1. Install, open the app, tap *Grant location*, then *Allow location all the time*
   so GPS keeps working with the phone locked in the car.
2. Wait for "N cameras loaded" in the status, then try *Simulate approach to nearest camera*
   to hear and see an alert without driving.

The phone screen shows the exact picture sent to the car.

## Experimental: cameras on the car's own map

The car's protocol has an undocumented map overlay service (`map_create`, `map_initializeImport`,
`map_showOverlay`). With the experimental toggle on, the app sends the cameras within 3 miles to it
and re-sends them every mile. Nobody has documented the file format it expects, so the first run
tries KML, KMZ, GPX, OV2 and CSV in turn and keeps the first one the car accepts.
Every step and the car's answers are written to `files/map_overlay.txt`:

    adb shell run-as io.bimmergestalt.idriveconnectaddons.speedcams cat files/map_overlay.txt

Even if it works, the native navigation will not warn about these pins; the warnings still come from this app.

## Limitations

- The digital cluster only shows the car's own navigation. The camera map is a separate app page,
  and the alerts are the popup, the phone audio and the notification.
- The car's planned route is not available, so "ahead" means the most likely continuation of the
  current road. A camera just after a turn you are about to take alerts once you have turned.
- Camera coverage and limits are only as good as OpenStreetMap. Mobile camera sites are rarely mapped.
- Cameras on the other carriageway of a dual carriageway may also alert, because OpenStreetMap
  rarely records which direction a camera faces.
- The map leaves the right ~40% for less important content, so it stays usable in split screen.

Data © OpenStreetMap contributors (ODbL). Map tiles © CARTO.
