#!/bin/bash

# Comprehensive fix for ALL message templates across all versions
# TICKET-002: Add isReferenced: true to clinical resources

set -e

echo "🔧 Fixing LinuxForHealth message templates across ALL versions"
echo ""

BASE_DIR="/Users/nagarajansankrithi/Documents/github/hl7omop/hl7v2-fhir-converter/src/main/resources/hl7"

# Version directories to process
VERSIONS=("message" "v2.3/message" "v2.5/message" "v2.6/message")

# Files to process in each version
FILES=(
  "ADT_A01.yml"
  "ADT_A03.yml"
  "ADT_A04.yml"
  "ADT_A05.yml"
  "ADT_A08.yml"
  "ADT_A28.yml"
  "ADT_A31.yml"
)

fix_allergy_intolerance() {
  local file="$1"
  # Check if AllergyIntolerance exists and doesn't already have isReferenced
  if grep -q "resourceName: AllergyIntolerance" "$file" && ! grep -A 3 "resourceName: AllergyIntolerance" "$file" | grep -q "isReferenced:"; then
    # Use awk to add isReferenced: true after repeats: true
    awk '/resourceName: AllergyIntolerance/,/additionalSegments:/ {
      if (/repeats: true/ && !added) {
        print
        print "    isReferenced: true"
        added=1
        next
      }
    }
    /resourceName: [^A]/ { added=0 }
    { print }' "$file" > "$file.tmp" && mv "$file.tmp" "$file"
    echo "    ✅ Fixed AllergyIntolerance"
    return 0
  fi
  return 1
}

fix_procedure() {
  local file="$1"
  # Check if Procedure exists and doesn't already have isReferenced
  if grep -q "resourceName: Procedure" "$file" && ! grep -A 3 "resourceName: Procedure" "$file" | grep -q "isReferenced:"; then
    awk '/resourceName: Procedure/,/additionalSegments:/ {
      if (/repeats: true/ && !added) {
        print
        print "    isReferenced: true"
        added=1
        next
      }
    }
    /resourceName: [^P]/ { added=0 }
    { print }' "$file" > "$file.tmp" && mv "$file.tmp" "$file"
    echo "    ✅ Fixed Procedure"
    return 0
  fi
  return 1
}

fix_coverage() {
  local file="$1"
  # Check if Coverage exists and doesn't already have isReferenced
  if grep -q "resourceName: Coverage" "$file" && ! grep -A 3 "resourceName: Coverage" "$file" | grep -q "isReferenced:"; then
    awk '/resourceName: Coverage/,/additionalSegments:/ {
      if (/repeats: true/ && !added) {
        print
        print "    isReferenced: true"
        added=1
        next
      }
    }
    /resourceName: [^C]/ { added=0 }
    { print }' "$file" > "$file.tmp" && mv "$file.tmp" "$file"
    echo "    ✅ Fixed Coverage"
    return 0
  fi
  return 1
}

fix_related_person() {
  local file="$1"
  # Check if RelatedPerson exists and doesn't already have isReferenced
  if grep -q "resourceName: RelatedPerson" "$file" && ! grep -A 3 "resourceName: RelatedPerson" "$file" | grep -q "isReferenced:"; then
    awk '/resourceName: RelatedPerson/,/additionalSegments:/ {
      if (/repeats: true/ && !added) {
        print
        print "    isReferenced: true"
        added=1
        next
      }
    }
    /resourceName: [^R]/ { added=0 }
    { print }' "$file" > "$file.tmp" && mv "$file.tmp" "$file"
    echo "    ✅ Fixed RelatedPerson"
    return 0
  fi
  return 1
}

total_fixed=0

for VERSION_DIR in "${VERSIONS[@]}"; do
  FULL_DIR="$BASE_DIR/$VERSION_DIR"
  
  if [ ! -d "$FULL_DIR" ]; then
    echo "⚠️  Directory not found: $VERSION_DIR"
    continue
  fi
  
  echo "📁 Processing $VERSION_DIR"
  
  for FILE in "${FILES[@]}"; do
    FULL_PATH="$FULL_DIR/$FILE"
    
    if [ ! -f "$FULL_PATH" ]; then
      continue
    fi
    
    echo "  🔧 $FILE"
    fixed=0
    
    fix_allergy_intolerance "$FULL_PATH" && ((fixed++)) || true
    fix_procedure "$FULL_PATH" && ((fixed++)) || true
    fix_coverage "$FULL_PATH" && ((fixed++)) || true
    fix_related_person "$FULL_PATH" && ((fixed++)) || true
    
    if [ $fixed -eq 0 ]; then
      echo "    ℹ️  No changes needed"
    else
      ((total_fixed+=fixed))
    fi
  done
  
  echo ""
done

echo "✅ COMPLETE: Fixed $total_fixed resource(s) across all versions"

