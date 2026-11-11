package com.san.bastionpay.core.dev;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** dev 프로파일 전용: /dev/error/404 처럼 호출해 에러 페이지를 미리 본다. */
@Profile("dev")
@Controller
public class DevErrorPreviewController {

    @GetMapping("/dev/error/{status}")
    public void preview(@PathVariable int status, HttpServletResponse response) throws IOException {
        response.sendError(status);
    }
}
