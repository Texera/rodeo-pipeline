# Turns each sequence into a 3D structure and builds a viewer for it.
#
# Two sources, in order:
#   1. ESMFold, through the public ESM Atlas endpoint -- a real structure
#      PREDICTION from the sequence alone.
#   2. The experimentally determined structure from the RCSB PDB, if the
#      prediction service is unavailable.
#
# Neither is AlphaFold 3. AF3's model weights are released by DeepMind only
# under a separate licence and are not in this image, so AlphaFold 3 here runs
# its data pipeline -- the evidence search upstream -- and not folding. Each row
# records which source produced its structure, so the two are never confused.
from pytexera import *

import json
import time
import urllib.error
import urllib.request

FOLD_ENDPOINT = "https://api.esmatlas.com/foldSequence/v1/pdb/"
RCSB_ENDPOINT = "https://files.rcsb.org/download/{pdb_id}.pdb"

# Experimental structures for the proteins in this demo. The prediction service
# is free and public, so it rate-limits and times out; without a fallback one
# 504 would take a whole protein out of the results.
KNOWN_STRUCTURES = {
    "P62979": "1UBQ",  # Ubiquitin
    "Q15843": "1NDD",  # NEDD8
    "P63165": "1A5R",  # SUMO1
    "P61956": "1WM3",  # SUMO2
}

# One quick attempt, then fall back. The prediction service is free and public,
# so it rate-limits and times out; waiting on it twice is what turns a ten-second
# run into one that outlives the caller's own timeout. RCSB answers in about a
# second, so failing over fast costs less than retrying.
FOLD_ATTEMPTS = 1
FOLD_TIMEOUT_SECONDS = 12


class ProcessTupleOperator(UDFOperatorV2):

    @overrides
    def process_tuple(self, tuple_: Tuple, port: int) -> Iterator[Optional[TupleLike]]:
        pdb_text, source = self._structure(tuple_["sequence"], tuple_["accession"])

        confidence = self._mean_confidence(pdb_text) if source.startswith("ESMFold") else None

        yield {
            "protein_name": tuple_["protein_name"],
            "accession": tuple_["accession"],
            "residues": tuple_["residues"],
            "msa_depth": tuple_["msa_depth"],
            "structure_source": source,
            "confidence_plddt": round(confidence, 1) if confidence is not None else -1.0,
            "structure_html": self._viewer(tuple_, pdb_text, source, confidence),
        }

    def _structure(self, sequence: str, accession: str) -> tuple:
        last_error = None
        for attempt in range(FOLD_ATTEMPTS):
            try:
                request = urllib.request.Request(
                    FOLD_ENDPOINT, data=sequence.encode(), headers={"Content-Type": "text/plain"}
                )
                text = urllib.request.urlopen(request, timeout=FOLD_TIMEOUT_SECONDS).read().decode()
                if "ATOM" in text:
                    return text, "ESMFold prediction"
                last_error = "response contained no atoms"
            except Exception as error:  # noqa: BLE001 - any failure falls back
                last_error = f"{type(error).__name__}: {error}"
                if attempt + 1 < FOLD_ATTEMPTS:
                    time.sleep(2)

        pdb_id = KNOWN_STRUCTURES.get(accession)
        if not pdb_id:
            raise RuntimeError(f"Could not fold {accession} ({last_error}) and no known structure to fall back on.")
        url = RCSB_ENDPOINT.format(pdb_id=pdb_id)
        text = urllib.request.urlopen(url, timeout=60).read().decode()
        return text, f"RCSB experimental ({pdb_id})"

    @staticmethod
    def _mean_confidence(pdb_text: str):
        # Per-residue confidence rides in the B-factor column of each CA atom.
        # ESMFold writes it 0-1; pLDDT is conventionally 0-100.
        values = [
            float(line[60:66])
            for line in pdb_text.splitlines()
            if line.startswith("ATOM") and line[12:16].strip() == "CA"
        ]
        if not values:
            return None
        mean = sum(values) / len(values)
        return mean * 100.0 if mean <= 1.0 else mean

    def _viewer(self, tuple_: Tuple, pdb_text: str, source: str, confidence) -> str:
        # json.dumps produces a correctly escaped JS string literal, so a PDB
        # containing quotes or backslashes cannot break out of the script.
        pdb_literal = json.dumps(pdb_text)
        detail = (
            f"predicted confidence pLDDT {confidence:.0f}/100"
            if confidence is not None
            else "experimentally determined structure"
        )
        return f"""<!doctype html>
<html><head><meta charset="utf-8">
<script src="https://cdn.jsdelivr.net/npm/3dmol@2.4.0/build/3Dmol-min.js"></script>
<style>
  body {{ margin:0; font-family:system-ui,-apple-system,sans-serif; background:#0f1115; color:#e8eaed; }}
  .bar {{ padding:10px 14px; border-bottom:1px solid #262a33; }}
  .name {{ font-size:15px; font-weight:600; }}
  .meta {{ font-size:12px; color:#9aa3b2; margin-top:3px; line-height:1.5; }}
  #v {{ position:relative; width:100%; height:440px; }}
</style></head>
<body>
  <div class="bar">
    <div class="name">{tuple_["protein_name"]} &middot; {tuple_["accession"]}</div>
    <div class="meta">
      {tuple_["residues"]} residues &middot; AlphaFold&nbsp;3 alignment depth {tuple_["msa_depth"]}<br>
      {source} &middot; {detail} &middot; coloured N&rarr;C
    </div>
  </div>
  <div id="v"></div>
<script>
  var viewer = $3Dmol.createViewer(document.getElementById("v"), {{backgroundColor:"#0f1115"}});
  viewer.addModel({pdb_literal}, "pdb");
  viewer.setStyle({{}}, {{cartoon:{{color:"spectrum"}}}});
  viewer.zoomTo();
  viewer.render();
  viewer.spin("y", 0.4);
</script>
</body></html>"""
