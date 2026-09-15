# AlphaFold 3's own MSA search, run on the sequences from the uploaded file.
#
# alphafold3.data.tools.jackhmmer is the class AF3's real data pipeline uses, and
# it shells out to the real jackhmmer binary. How deep an alignment a protein has
# is the standard predictor of whether a structure prediction can be trusted.
from pytexera import *

import os
import shutil
import sys
import tempfile
import time

REFERENCE_DB = "/datasets/biologist@demo.org/protein-structures/v1 - initial/ubiquitin_family.fasta"


class ProcessTupleOperator(UDFOperatorV2):

    @overrides
    def open(self):
        from alphafold3.data.tools import jackhmmer

        # jackhmmer needs a real path on disk, so the reference database is pulled
        # out of the dataset once per worker rather than once per protein.
        self.database_path = os.path.join(tempfile.gettempdir(), "reference_db.fasta")
        if not os.path.exists(self.database_path):
            with open(self.database_path, "wb") as handle:
                handle.write(DatasetFileDocument(REFERENCE_DB).read_file().read())

        self.jackhmmer_path = shutil.which("jackhmmer")
        if not self.jackhmmer_path:
            raise RuntimeError(
                "jackhmmer is not on PATH -- this unit is running the stock engine "
                "image rather than the alphafold3 runtime image."
            )

        # n_iter=1 keeps it quick; AF3's own default is 3 iterations.
        self.searcher = jackhmmer.Jackhmmer(
            binary_path=self.jackhmmer_path,
            database_path=self.database_path,
            n_cpu=2,
            n_iter=1,
            max_sequences=500,
        )
        self.python_version = ".".join(str(part) for part in sys.version_info[:3])

    @overrides
    def process_tuple(self, tuple_: Tuple, port: int) -> Iterator[Optional[TupleLike]]:
        sequence = tuple_["sequence"]

        started = time.time()
        result = self.searcher.query(sequence)
        elapsed = time.time() - started

        # An a3m carries one FASTA header per aligned sequence, so counting the
        # headers gives the depth of the alignment.
        msa_depth = result.a3m.count(">")

        yield {
            "protein_name": tuple_["protein_name"],
            "accession": tuple_["accession"],
            "what_it_does": tuple_["what_it_does"],
            "sequence": sequence,
            "residues": len(sequence),
            "msa_depth": msa_depth,
            "search_seconds": round(elapsed, 3),
            "alphafold_python": self.python_version,
        }
