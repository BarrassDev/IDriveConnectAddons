# Nav Mirror

A Car Connection Addon that mirrors your phone navigation into the car,
so that the car's own navigation (and with it the digital instrument cluster and head-up display)
follows the destination you picked in Google Maps or Waze.

## What it does

- **Captures the destination** when you share a place or a route from Google Maps or Waze to
  *Send to car navigation* (the addon's share target). It understands `geo:` links, `google.navigation:` links,
  Google Maps links (including `maps.app.goo.gl` short links and plus codes), Waze links (`waze.com/ul`, `waze://`),
  and plain addresses.
- **Starts the car's native navigation** to that destination through the Connected Apps `navigate` action,
  the same mechanism AAIdrive and the official BMW apps use. The car routes itself, which is what makes the
  route appear on the digital dash. If the car isn't connected yet, the destination is remembered and sent as soon as it connects.
- **Shows the phone navigation status in the car**: with notification access granted, the Google Maps / Waze
  turn-by-turn notification (next turn, distance, ETA) is parsed and listed in the car app, together with the
  captured destination and a row to (re)start the car navigation.

## Why sharing is needed

Neither Google Maps nor Waze exposes the active route to other apps. Their ongoing notifications only carry the
next maneuver and the ETA, not the destination coordinates. Sharing the place is a one-tap step that gives the
addon the destination; everything after that is automatic. The experimental setting *look up destination names
found in notifications* tries to geocode a destination name if a notification happens to mention one
("Driving to Home"), but that depends on the app's wording and is off by default.

## Requirements

- AAIdrive installed, connected to the car, and this addon enabled in its Addons tab.
- A car with native navigation (the `navigate` action only works where the car can route by itself).
- Notification access for the live turn-by-turn display (optional, the destination sharing works without it).
- To build: the SmartThings Classic APK in the `external` directory, like for the `screen_mirror` addon.
  Its car app layout (Basecore OnlineServices v2) includes the `navigate` link action.

## Structure

- `NavMirror` holds the shared state (phone navigation, captured destination, car connection) and pushes
  the destination to the car.
- `nav/DestinationParser` turns links and text into a `Destination`.
- `nav/NavNotificationListener`, `NotificationTextExtractor`, `NavNotificationParser` read the navigation apps' notifications.
- `carapp/CarApp` connects to the car, `carapp/NavigationTrigger` fires the navigate action,
  `carapp/views/StatusView` is the screen in the car.
