# Security Policy

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues, pull requests, or
discussions.**

Email **<admin@counters.dev>** instead. If you prefer, you can also use GitHub's
[private vulnerability reporting](https://docs.github.com/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
on this repository.

Please include as much of the following as you can:

- the SDK (language) and version, or the commit, affected;
- a description of the issue and what an attacker could achieve with it;
- steps to reproduce, ideally a minimal proof of concept;
- any suggested fix or mitigation you have in mind.

You will get an acknowledgement within **3 business days**, and an assessment with a plan and a
timeline within **10 business days**. We will keep you updated as the fix progresses, and we are happy
to credit you in the advisory once the fix is released — tell us how you would like to be named, or
that you would rather stay anonymous.

Please give us a reasonable opportunity to release a fix before disclosing the issue publicly.

## Scope

This repository contains the client SDKs. Issues in scope include, for example: a client that fails to
validate input it promises to validate, that leaks an API key into logs or error messages, that
mishandles TLS, or that mis-parses a response in a way an attacker could exploit.

Vulnerabilities in the **counters.dev service itself** (the API at `api.counters.dev`) are also in
scope for this address — report them the same way.

## Handling API keys

If you believe an API key of yours has been exposed, rotate it from your counters.dev dashboard
immediately, then email <admin@counters.dev> if you need help. Never paste a real API key into an
issue, a pull request, a test fixture, or an example.
