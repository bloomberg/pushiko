## OK 200

### Verification only

```json
{
  "name": "projects/foo/messages/fake_message_id"
}
```

## Invalid registration token

```json
{
  "error": {
    "code": 400,
    "message": "Request contains an invalid argument.",
    "status": "INVALID_ARGUMENT",
    "details": [{
      "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
      "errorCode": "INVALID_ARGUMENT"
     }, {
      "@type": "type.googleapis.com/google.rpc.BadRequest",
      "fieldViolations": [{
        "field": "message.token",
        "description": "Invalid registration token"
      }]
    }]
  }
}
```

## Missing registration token

```json
{
  "error": {
    "code": 400,
    "message": "Recipient of the message is not set.",
    "status": "INVALID_ARGUMENT",
    "details": [{
      "@type": "type.googleapis.com/google.rpc.BadRequest",
      "fieldViolations": [{
        "field": "message",
        "description": "Recipient of the message is not set."
      }]
    }, {
      "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
      "errorCode": "INVALID_ARGUMENT"
    }]
  }
}
```

## Payload too large

```json
{
  "error": {
    "code": 400,
    "message": "Request contains an invalid argument.",
    "status": "INVALID_ARGUMENT"
  }
}
```

## Unregistered token

```json
{
  "error": {
    "code": 404,
    "message": "Requested entity was not found.",
    "status": "NOT_FOUND",
    "details": [{
      "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
      "errorCode": "UNREGISTERED"
    }]
  }
}
```

## Internal server error

```json
{
  "error": {
    "code": 500,
    "message": "Internal error encountered.",
    "status": "INTERNAL",
    "details": [{
      "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
      "errorCode": "INTERNAL"
    }]
  }
}
```

## Bad gateway

```html
<!DOCTYPE html>
<html lang=en>
  <meta charset=utf-8>
  <meta name=viewport content="initial-scale=1, minimum-scale=1, width=device-width">
  <title>Error 502 (Server Error)!!1</title>
  <style>
    *{margin:0;padding:0}html,code{font:15px/22px arial,sans-serif}html{background:#fff;color:#222;padding:15px}body{margin:7% auto 0;max-width:390px;min-height:180px;padding:30px 0 15px}* > body{background:url(//www.google.com/images/errors/robot.png) 100% 5px no-repeat;padding-right:205px}p{margin:11px 0 22px;overflow:hidden}ins{color:#777;text-decoration:none}a img{border:0}@media screen and (max-width:772px){body{background:none;margin-top:0;max-width:none;padding-right:0}}#logo{background:url(//www.google.com/images/branding/googlelogo/1x/googlelogo_color_150x54dp.png) no-repeat;margin-left:-5px}@media only screen and (min-resolution:192dpi){#logo{background:url(//www.google.com/images/branding/googlelogo/2x/googlelogo_color_150x54dp.png) no-repeat 0% 0%/100% 100%;-moz-border-image:url(//www.google.com/images/branding/googlelogo/2x/googlelogo_color_150x54dp.png) 0}}@media only screen and (-webkit-min-device-pixel-ratio:2){#logo{background:url(//www.google.com/images/branding/googlelogo/2x/googlelogo_color_150x54dp.png) no-repeat;-webkit-background-size:100% 100%}}#logo{display:inline-block;height:54px;width:150px}
  </style>
  <a href=//www.google.com/><span id=logo aria-label=Google></span></a>
  <p><b>502.</b> <ins>That?s an error.</ins>
  <p>The server encountered a temporary error and could not complete your request.<p>Please try again in 30 seconds.  <ins>That?s all we know.</ins>
```

## Unavailable

```json
{
  "error": {
    "code": 503,
    "message": "The service is currently unavailable.",
    "status": "UNAVAILABLE",
    "details": [
      {
        "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
        "errorCode": "UNAVAILABLE"
      }
    ]
  }
}
```
