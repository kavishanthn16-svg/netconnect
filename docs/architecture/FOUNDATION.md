# NetConnect Foundation

## Scope

The foundation implements the SRS repository/build contract without implementing chat, UDP discovery, NIO transfer, or authentication/TLS behavior. It owns the Maven reactor, common contracts, non-secret configuration, server launcher, and JavaFX shell.

## Modules

- `common`: Jackson envelope, protocol/event/error types, configuration, DTOs, and service interfaces.
- `server`: headless launcher and future server-owned service packages. It depends on `common`.
- `client`: JavaFX shell and future client-owned feature packages. It depends on `common`, never `server`.

There are no cyclic dependencies. Java networking, NIO, and JSSE remain JDK APIs.

## Package ownership

The SRS assigns `server/session/**`, `server/bootstrap/**`, `client/shell/**`, `common/**`, and root build files to 234106T during the approved foundation phase. Other feature packages contain only directory placeholders in this branch.

## Ports and runtime paths

- TLS/TCP control: `5050`
- UDP presence hints: `5051`
- NIO file transfer: `5052`
- Server staging: `staging`
- Client downloads: `downloads`

Values are configurable through `.env.example` names or Java system properties. No secret is part of the shared configuration.

## Branch workflow

`main` is stable. `development` is integration. The foundation branch is based on `a5ae6dacfa0f9b5c73ae9a1dcfd261a82fa240cb`. The foundation PR must be reviewed and merged before members create feature branches from the resulting `origin/development` SHA.

Shared protocol changes require a CONTRACT-CHANGE issue and reviewed PR approved by affected owners.
