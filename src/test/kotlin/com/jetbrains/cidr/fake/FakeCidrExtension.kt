package com.jetbrains.cidr.fake

/**
 * Test stand-in whose FQN starts with `com.jetbrains.cidr.` so it passes the
 * package filter in `NativeDebugLauncher.candidateLoaders`. Lives in test
 * sources only.
 */
class FakeCidrExtension
