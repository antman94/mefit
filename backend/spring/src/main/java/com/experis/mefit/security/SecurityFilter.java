package com.experis.mefit.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.experis.mefit.security.models.Credentials;
import com.experis.mefit.security.models.Credentials.CredentialType;
import com.experis.mefit.security.models.SecurityProperties;
import com.experis.mefit.security.models.User;
import com.experis.mefit.security.roles.RoleConstants;
import com.experis.mefit.security.roles.RoleService;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.google.firebase.auth.FirebaseAuth;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class SecurityFilter extends OncePerRequestFilter {

	@Autowired
	private SecurityService securityService;

	@Autowired
	private CookieService cookieUtils;

	@Autowired
	private SecurityProperties securityProps;

	@Autowired
	RoleService securityRoleService;

	@Autowired
	private FirebaseAuth firebaseAuth;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		authorize(request);
		filterChain.doFilter(request, response);
	}

	private void authorize(HttpServletRequest request) {
		String sessionCookieValue = null;
		FirebaseToken decodedToken = null;
		CredentialType type = null;
		// Token verification
		boolean strictServerSessionEnabled = securityProps.getFirebaseProps().isEnableStrictServerSession();
		Cookie sessionCookie = cookieUtils.getCookie("session");
		String token = securityService.getBearerToken(request);
		try {
			if (sessionCookie != null) {
				sessionCookieValue = sessionCookie.getValue();
				decodedToken = firebaseAuth.verifySessionCookie(sessionCookieValue,
						securityProps.getFirebaseProps().isEnableCheckSessionRevoked());
				type = CredentialType.SESSION;
			} else if (!strictServerSessionEnabled && token != null && !token.equals("null")
					&& !token.equalsIgnoreCase("undefined")) {
				decodedToken = firebaseAuth.verifyIdToken(token);
				type = CredentialType.ID_TOKEN;
			}
		} catch (FirebaseAuthException e) {
			log.error("Firebase Exception:: ", e.getLocalizedMessage());
		}
		List<GrantedAuthority> authorities = new ArrayList<>();
		User user = firebaseTokenToUserDto(decodedToken);
		// Handle roles
		if (user != null) {
			// Handle Super Role
			if (securityProps.getSuperAdmins().contains(user.getEmail())) {
				if (!decodedToken.getClaims().containsKey(RoleConstants.ROLE_ADMIN)) {
					try {
						securityRoleService.addRole(decodedToken.getUid(), RoleConstants.ROLE_ADMIN);
					} catch (Exception e) {
						log.error("Super Role registeration expcetion ", e);
					}
				}
				authorities.add(new SimpleGrantedAuthority(RoleConstants.ROLE_ADMIN));
			}
			// Handle Other roles
			decodedToken.getClaims().forEach((k, v) -> authorities.add(new SimpleGrantedAuthority(k)));
			// Set security context
			UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(user,
					new Credentials(type, decodedToken, token, sessionCookieValue), authorities);
			authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
			SecurityContextHolder.getContext().setAuthentication(authentication);
		}
	}

	private User firebaseTokenToUserDto(FirebaseToken decodedToken) {
		User user = null;
		if (decodedToken != null) {
			user = new User();
			user.setUid(decodedToken.getUid());
			user.setDisplayName(decodedToken.getName());
			user.setEmail(decodedToken.getEmail());
			user.setEmailVerified(decodedToken.isEmailVerified());
			user.setIssuer(decodedToken.getIssuer());
		}
		return user;
	}

}
