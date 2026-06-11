package main

import (
	"github.com/metacubex/mihomo/component/dnsauth"
	"github.com/metacubex/mihomo/hub/route"
)

func init() {
	route.LogPayloadProcessor = dnsauth.MaskLogPayload
}
