package com.example.mcpchat.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WebController {

    @Value("${app.oauth-server-url}")
    private String oauthServerUrl;

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("oauthServerUrl", oauthServerUrl);
        return "index";
    }

    @GetMapping("/chat")
    public String chat() {
        return "chat";
    }
}