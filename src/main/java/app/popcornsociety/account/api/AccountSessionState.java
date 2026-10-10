package app.popcornsociety.account.api;

import org.springframework.modulith.NamedInterface;

@NamedInterface("identity")
public record AccountSessionState(long securityVersion, boolean locked, boolean enabled) {}
