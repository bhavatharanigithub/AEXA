package com.aexa.platform.api;

import com.aexa.platform.api.dto.PlatformCapabilitiesResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {

    @GetMapping("/capabilities")
    public PlatformCapabilitiesResponse capabilities() {
        return new PlatformCapabilitiesResponse(5, true, true, true, true, true, true, true, true);
    }
}
