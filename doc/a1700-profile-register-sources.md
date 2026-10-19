# A1700 profile register sources

Reviewed 2026-09-16. This is a source inventory and evidence-based partial map,
not a manufacturer protocol specification. No profile commands were sent during
this research.

## Strongest local evidence

Base directory: `/home/shou/Documents/ATEnergy/Meter/A1700`.

`PMU_A1700_Registers_210930.xlsx` contains captured PMU conversations, including
the serial-number response `--------21000568`, matching the connected meter.
Its identification string also matches: `/GEC5090100301113@000`.

| Sheet and cells | Observed operation | What the evidence establishes |
| --- | --- | --- |
| Full Load Profile, A11:A12 | R1 798001(10) | Captured meter serial is 21000568 |
| Full Load Profile, A15:A18 | Second P2 authentication, then W1 551001(FFFF) | A setup write is used before reading profile blocks; exact selector semantics are not documented here |
| Full Load Profile, A19:A20 | R1 551001(02), response 0076 | Two-byte result interpreted as hex 0076 equals 118, matching the observed block count |
| Full Load Profile, A21:A256 | R1 550001(40) through 550076(40) | 118 sequential block requests; requested size 0x40 = 64 decoded bytes |
| Instrumentation profile data, A15:A18 | Second P2 authentication, then W1 556001(FFFF) | Separate setup/control address for instrumentation profile |
| Instrumentation profile data, A19:A20 | R1 556001(02), response 0038 | Hex 0038 equals 56, matching the observed block count |
| Instrumentation profile data, A21:A132 | R1 555001(40) through 555038(40) | 56 sequential instrumentation block requests |
| Load Profiling, A18:A25 | R1 878001(03), 777001(02), 797001(20), 553001(01) | Codes read during PMU's load-profiling configuration retrieval; individual field meanings remain unverified |
| Inst. Profiling, A18:A23 | R1 797001(20), 775001(10), 776001(06) | Codes read during instrumentation-profiling configuration retrieval; individual field meanings remain unverified |

Do not confuse a 64-byte transport block with one profile interval: records may
span blocks and may contain event markers or changing channel configuration.

`PMU_register.xlsx` and `Logfile_loadprofile_PMU.xlsx` also contain profile logs.
`A1700/logloadprofilepmu220317.txt` provides another sequence:

```text
W1 551001(0200)
R1 551001(02) -> (0011)
R1 550001(40)
...
R1 550011(40)
```

This second example has 17 blocks, consistent with 0x0011. It supports the
block-count interpretation, but does not by itself define what `(0200)` means.
In particular, do not assume it means a date or a number of days without further
evidence. The meaning and side effects of the setup write need verification
before implementing profile selection.

## Manufacturer manuals in the directory

`a1700_hardware_manual.pdf`, M120 001 3S, May 2007:

- Section 5.3: Data Stream Mode capabilities.
- Sections 11 and 11.1, printed pages 20-21: load profile and status information.
- Section 20.2, printed pages 28-29: instrumentation profiling.
- Figure 7, printed page 55 (PDF page 57): access levels. Level 0 allows serial
  number and scheme ID; level 1 includes half-hourly active-energy values.

`A1700_Manual.pdf` includes the hardware material and PMU documentation:

- PMU section 3.4.11: load-profile configuration.
- PMU section 4.1.1: Data Stream Mode. Its serial-port requirements must not be
  confused with the currently demonstrated 9600 7E1 R1 ASCII exchanges.

These manuals explain features and configuration, but the inspected sections do
not supply a complete native register map or the binary profile record format.

## OBIS files are not native command maps

`OBIS_Code_A1700.xlsx`, sheet `OBIS Code`, A1:H9, explicitly labels its code
column **OBIS Display Code**. For example, it maps total import kWh to `1.8.0`.
`OBIS_A1700.xlsx` has the same kind of display mapping.

These files do not establish how to turn `1.8.0` into an A1700 R1 command, nor
how to select or decode a load-profile block. Do not substitute DLMS Profile
Generic requests or Modbus addresses for the observed A1700 IEC command path.

`TIME_BYTE_A1700.xlsx` contains date/hex examples; it is not a complete timestamp
encoding specification. `Loadprofile/LoadprofileA1700_220217.xlsx` provides
decoded PMU profile values for comparison. The two inspected `convertloadprofile`
workbooks expose no useful cell-based decoder (their visible cell dimensions are
1 by 1); embedded objects were not assessed.

## Online findings

- Honeywell/Elster A1700 datasheet: confirms load profile, instrumentation profile
  and Data Stream Mode, but supplies no native register table:
  https://prod-edam.honeywell.com/content/dam/honeywell-edam/pmt/hps/products/sme/electricity/commercial-and-industrial-meters/a1700/A1700-31052012.pdf
- Elster-authored brochure hosted by a distributor explicitly lists **Protocol
  Available under Licence**:
  https://phuonglai.com/upload/tai_lieu_elster_a1700.pdf
- Gurux's implementation note documents session establishment and an R1 serial
  read example; it is not a profile register/decoder specification:
  https://www.gurux.fi/node/5415
- EnergyICT's 2010 release notes for its ABBA1700 driver describe normal IEC1107
  versus DSM profile retrieval, the IEC1107Compatible option, and security
  settings. Its OBIS tables describe the driver's exported register mapping;
  they are not a complete native R1 profile register/record specification:
  https://www.scribd.com/document/519880176/Elster-ABB-A1700-IEC1107

No complete publicly accessible manufacturer native register/profile protocol
specification was located in this search. This does not establish that none
exists. Request from Honeywell/Elster or the meter supplier the A1700 IEC
62056-21 application protocol/register map and load/instrumentation profile
record format applicable to device identification `/GEC5090100301113@000`.
That wording describes the needed document; it is not a verified publication title.

## What is still needed for reliable profile reading

1. Authorized profile-read authentication (the logs show a second P2 exchange).
   Previously captured P2 responses are challenge-dependent and cannot simply
   be replayed in a new session.
2. Exact meaning, byte order and side effects of selectors 551001 / 556001.
3. Block-count, last-block and concurrent-recording behavior.
4. Record/event markers, timestamp epoch/time zone/DST handling, channel order,
   numeric encoding, units and scaling.
5. Comparison against PMU export from the same meter and time window.

The existing logs can support a partial implementation and offline decoding
investigation, but should not be presented as a complete register specification.
