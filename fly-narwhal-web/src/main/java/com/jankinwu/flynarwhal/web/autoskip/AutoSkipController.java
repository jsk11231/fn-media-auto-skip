package com.jankinwu.flynarwhal.web.autoskip;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/autoskip")
public class AutoSkipController {
    private final AutoSkipService autoSkipService;

    @GetMapping("/dashboard")
    public AutoSkipModels.ApiResponse<AutoSkipModels.Dashboard> dashboard() {
        return AutoSkipModels.ApiResponse.ok("ok", autoSkipService.dashboard());
    }

    @PostMapping("/connect")
    public AutoSkipModels.ApiResponse<Void> connect(@RequestBody AutoSkipModels.ConnectRequest request) {
        String message = autoSkipService.connect(request.getBaseUrl(), request.getUsername(), request.getPassword());
        return AutoSkipModels.ApiResponse.ok(message, null);
    }

    @PostMapping("/settings")
    public AutoSkipModels.ApiResponse<Void> settings(@RequestBody AutoSkipModels.SettingsRequest request) {
        autoSkipService.updateSettings(request);
        return AutoSkipModels.ApiResponse.ok("设置已保存", null);
    }

    @PostMapping("/scan")
    public AutoSkipModels.ApiResponse<Void> scan(@RequestParam(defaultValue = "false") boolean force) {
        return AutoSkipModels.ApiResponse.ok(autoSkipService.startScan(force), null);
    }

    @PostMapping("/apply/{seasonGuid}")
    public AutoSkipModels.ApiResponse<Void> apply(@PathVariable String seasonGuid,
                                                   @RequestParam(defaultValue = "false") boolean force) {
        return AutoSkipModels.ApiResponse.ok(autoSkipService.apply(seasonGuid, force), null);
    }

    @ExceptionHandler(Exception.class)
    public AutoSkipModels.ApiResponse<Void> handleException(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return AutoSkipModels.ApiResponse.error(message);
    }
}
