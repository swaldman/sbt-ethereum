# ethUtil*

Some utilities that may be useful when interacting with _Ethereum_ smart contracts.

See also @ref[`ensNameHashes`](../../ens.md#ensnamehashes)

### ethUtilFunctionIdentifier

@@@ div { .keydesc }

**Usage:**
```
> ethUtilFunctionIdentifier <name> [argument-type] [argument-type]...
```

Computes the canonical function signature and four-byte function identifier of a standard Ethereum smart contract function,
given the function name and argument types. For no argument functions, just provide a name with no argument types.

See also @ref:[ethContractAbiCallEncode](../contract/abi.md#ethcontractabicallencode) and @ref:[ethContractAbiCallDecode](../contract/abi.md#ethcontractabicalldecode).

**Example:**
```
> ethUtilFunctionIdentifier addFortune string
[info] Canonical signature: addFortune(string)
[info] Identifier: 0x4cf373e6
```

@@@

### ethUtilHashKeccak256

@@@ div { .keydesc}

**Usage:**
```
> ethUtilHashKeccak256 <hex-string>
```

Computes the _Ethereum_-standard Keccak256 hash of a given bytestring.

**Example:**
```
> ethUtilHashKeccak256 0xabcdef
[info] 0x800d501693feda2226878e1ec7869eef8919dbc5bd10c2bcd031b94d73492860
[success] Total time: 1 s, completed Nov 9, 2019 10:33:43 PM
```
@@@

### ethUtilTimeIsoNow

@@@ div { .keydesc}

**Usage:**
```
> ethUtilTimeIsoNow
```
Prints the current time in ISO formats that are acceptable to @ref:[`ethUtilTimeUnix`](#ethutiltimeunix).

The main purpose of this task is to give users a template time format they can edit to get a desired UNIX time. See @ref:[`ethUtilTimeUnix`](#ethutiltimeunix).

**Example:**
```
> ethUtilTimeIsoNow
[info] 2019-11-10T06:37:24.686Z (UTC) or 2019-11-09T22:37:24.686-08:00 (local)
[success] Total time: 0 s, completed Nov 9, 2019 10:37:25 PM
```
@@@

### ethUtilTimeUnix

@@@ div { .keydesc}

**Usage:**
```
> ethUtilTimeUnix [optional-ISO-timestamp]
```
Accepts a timestamp in any format output by @ref:[`ethUtilTimeIsoNow`](#ethutiltimeisonow), and converts it into UNIX-epoch time, in both seconds and milliseconds.

If no timestamp is specified, the current time is logged.

**Example (with optional argument):**
```
> ethUtilTimeUnix 2019-11-09T22:37:24.686-08:00
[info] 1573367844 seconds, or 1573367844686 milliseconds, into the UNIX epoch
[success] Total time: 0 s, completed Nov 9, 2019 10:41:21 PM
```

**Example (no argument, current time):**
```
> ethUtilTimeUnix
[info] 1573368165 seconds, or 1573368165534 milliseconds, into the UNIX epoch (according to the system clock)
[success] Total time: 0 s, completed Nov 9, 2019 10:42:45 PM
```
@@@

