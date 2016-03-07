package org.projectlombok.security.totpexample.servlets;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.projectlombok.security.totpexample.Session;
import org.projectlombok.security.totpexample.SessionStore;
import org.projectlombok.security.totpexample.Totp;
import org.projectlombok.security.totpexample.Totp.TotpData;
import org.projectlombok.security.totpexample.UserStore;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

/**
 * This servlet serves the page to verify that a logging user has the TOTP device they signed up with, by asking for the TOTP code being shown on their device.
 * 
 * You get here from the {@link LoginServlet}, and the submissions to the form generated by this servlet are handled by {@link ConfirmTotpLoginServlet}.
 */
public class VerifyTotpServlet extends HttpServlet {
	// SECURITY NOTE: TODO - explain this in some more detail.
	private static final long DEFAULT_TIME_TO_LIVE = TimeUnit.MINUTES.toMillis(30);
	private static final long LOGIN_TIME_TO_LIVE = TimeUnit.MINUTES.toMillis(10);
	
	private final UserStore users;
	private final SessionStore sessions;
	private final Template verifyTotpTemplate;
	private final Totp totp;
	
	public VerifyTotpServlet(Configuration templates, UserStore users, SessionStore sessions, Totp totp) throws IOException {
		this.verifyTotpTemplate = templates.getTemplate("verifyTotp.html");
		this.users = users;
		this.sessions = sessions;
		this.totp = totp;
	}
	
	/*
	 * GET calls to this servlet normally only occur when the TOTP verification fails and the user is redirected back.
	 */
	@Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		Session session = sessions.get(request.getParameter("si"));
		String username = "";
		if (session != null) {
			username = session.getOrDefault("username", "");
		}
		
		if (username.isEmpty()) {
			error(request, response, "Your session has expired; please log in again.");
			return;
		}
		
		TotpData totpData = totp.startCheckTotp(username);
		if (totpData.isLockedOut()) {
			response.sendRedirect("/troubleshoot-totp?si=" + session.getSessionKey());
			return;
		}
		
		renderPage(response, session, totpData);
	}
	
	/*
	 * POST calls to this servlet normally only occur when the user submits the login form.
	 */
	@Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String username = request.getParameter("username");
		String password = request.getParameter("password");
		
		if (username == null || username.isEmpty()) {
			error(request, response, "You need to type in your username.");
			return;
		}
		
		if (!users.userExists(username)) {
			/* SECURITY NOTE:
			 * It is a common practice and security suggestion that a login form, whether the username doesn't exist or the password was wrong, generate the same error message.
			 * 
			 * This security advice is at this point outdated and no longer recommended. In the vast majority of cases, trying to figure out if a user name exists can be done via the signup
			 * form, which means the added security of this measure has become irrelevant.
			 * 
			 * If you truly need to make it hard for an attacker to create a database of valid usernames, it needs a heck of a lot more thought and effort than generating the same error message.
			 * If, like almost all web applications, a list of valid usernames is not a security issue, then it is just user hostile not to tell them which part they messed up.
			 * 
			 * Therefore, we just tell the user, and so should you.
			 */
			error(request, response, "That username does not exist.");
			return;
		}
		
		if (password == null || password.isEmpty()) {
			error(request, response, "Please choose a password.");
			return;
		}
		
		if (!users.verifyPassword(username, password.toCharArray())) {
			error(request, response, "You did not enter the right password.");
			// TODO either talk about how we intentionally didn't worry about password bashing here, or we need to protect against it. Via IP or username or what?
			// probably just a talk, covering:
			// * How IP based limiting does not stop dedicated hackers, who have botnets, and will silently do the wrong thing when source IP info is not propagated properly due to reverse proxy or load balancer scenarios.
			// * The better approach is whitelisting: Aggressively lock out users, which is an easy Denial of Service problem, but create cookies on machines that have been used before to give them rights to bust through
			//   the lockout mechanism.
			// * BCrypt itself adds a modicum of security here no matter what you do, and for this demo we considered it 'good enough'.
			// * Relying on CAPTCHA's is not great as these can be busted with mechanical turk and other tricks.
			// * In general, you can lock out any given username for increasing time (first time: 15 sec, second time: 60s, etc). This can be 'busted' either with a cookie that has been left on the device as 'this device is more trusted', or by entering 4 consecutive TOTP codes.
			return;
		}
		
		TotpData totpData = totp.startCheckTotp(username);
		Session session = sessions.create(LOGIN_TIME_TO_LIVE);
		session.put("username", username);
		
		if (totpData.isLockedOut()) {
			response.sendRedirect("/troubleshoot-totp?si=" + session.getSessionKey());
			return;
		}
		
		renderPage(response, session, totpData);
	}
	
	private void renderPage(HttpServletResponse response, Session session, TotpData totpData) throws IOException, ServletException {
		Map<String, Object> root = new HashMap<>();
		root.put("key", session.getSessionKey());
		String error = session.getOrDefault("errMsg", "");
		if (!error.isEmpty()) {
			root.put("errMsg", error);
		}
		
		root.put("correctTotpCode", SetupTotpServlet.calculateCode(totpData.getSecret(), 0L));
		response.setContentType("text/html; charset=UTF-8");
		try (Writer out = response.getWriter()) {
			verifyTotpTemplate.process(root, out);
		} catch (TemplateException e) {
			throw new ServletException("Template broken: verifyTotp.html", e);
		}
	}
	
	private void error(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
		Session session = sessions.create(DEFAULT_TIME_TO_LIVE);
		session.put("errMsg", message);
		response.sendRedirect("/login?si=" + session.getSessionKey());
	}
}
