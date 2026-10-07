package com.chinhbean.realtimechat.controller;
import com.chinhbean.realtimechat.service.ChatSessions;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@Controller
public class MainController {
    private final ChatSessions sessions;
    public MainController(ChatSessions sessions) { this.sessions = sessions; }
    private void token(HttpSession session, Model model) {
        if (session.getAttribute("csrfToken") == null) session.setAttribute("csrfToken", UUID.randomUUID().toString());
        model.addAttribute("csrfToken", session.getAttribute("csrfToken"));
    }
    @GetMapping("/") public String index(HttpServletRequest request, Model model) {
        var session = request.getSession(false);
        String username = session == null ? null : (String) session.getAttribute("username");
        if (session == null || !sessions.active(username, session.getId())) return "redirect:/login";
        model.addAttribute("username", username); token(session, model); return "chat";
    }
    @GetMapping("/login") public String showLoginPage(HttpServletRequest request, Model model) {
        token(request.getSession(), model); return "login";
    }
    @PostMapping("/login") public String doLogin(HttpServletRequest request, Model model,
            @RequestParam(defaultValue = "") String username) {
        username = username.trim();
        if (!username.matches("[A-Za-z0-9_-]{1,32}")) {
            model.addAttribute("error", "Use 1-32 letters, numbers, underscores or hyphens.");
            token(request.getSession(), model); return "login";
        }
        var previous = request.getSession(false);
        if (previous != null) previous.invalidate();
        var session = request.getSession(true);
        if (!sessions.reserve(username, session.getId())) {
            model.addAttribute("error", "Username already in use. Choose another.");
            token(session, model); return "login";
        }
        session.setAttribute("username", username); token(session, model); return "redirect:/";
    }
    @PostMapping("/logout") public String logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        return "redirect:/login";
    }
}
