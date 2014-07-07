// Copyright 2013 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <map>
#include <utility>

#include "cc/resources/picture_pile.h"
#include "cc/test/fake_content_layer_client.h"
#include "cc/test/fake_rendering_stats_instrumentation.h"
#include "testing/gtest/include/gtest/gtest.h"
#include "ui/gfx/rect_conversions.h"
#include "ui/gfx/size_conversions.h"

namespace cc {
namespace {

class TestPicturePile : public PicturePile {
 public:
  using PicturePile::buffer_pixels;
  using PicturePile::CanRasterSlowTileCheck;
  using PicturePile::Clear;

  PictureMap& picture_map() { return picture_map_; }
  const gfx::Rect& recorded_viewport() const { return recorded_viewport_; }

  bool CanRasterLayerRect(const gfx::Rect& layer_rect) {
    return CanRaster(1.f, layer_rect);
  }

  typedef PicturePile::PictureInfo PictureInfo;
  typedef PicturePile::PictureMapKey PictureMapKey;
  typedef PicturePile::PictureMap PictureMap;

 protected:
    virtual ~TestPicturePile() {}
};

TEST(PicturePileTest, SmallInvalidateInflated) {
  FakeContentLayerClient client;
  FakeRenderingStatsInstrumentation stats_instrumentation;
  scoped_refptr<TestPicturePile> pile = new TestPicturePile;
  SkColor background_color = SK_ColorBLUE;

  float min_scale = 0.125;
  gfx::Size base_picture_size = pile->tiling().max_texture_size();

  gfx::Size layer_size = base_picture_size;
  pile->Resize(layer_size);
  pile->SetTileGridSize(gfx::Size(1000, 1000));
  pile->SetMinContentsScale(min_scale);

  // Update the whole layer.
  pile->Update(&client,
               background_color,
               false,
               gfx::Rect(layer_size),
               gfx::Rect(layer_size),
               1,
               &stats_instrumentation);

  // Invalidate something inside a tile.
  gfx::Rect invalidate_rect(50, 50, 1, 1);
  pile->Update(&client,
               background_color,
               false,
               invalidate_rect,
               gfx::Rect(layer_size),
               2,
               &stats_instrumentation);

  EXPECT_EQ(1, pile->tiling().num_tiles_x());
  EXPECT_EQ(1, pile->tiling().num_tiles_y());

  TestPicturePile::PictureInfo& picture_info =
      pile->picture_map().find(TestPicturePile::PictureMapKey(0, 0))->second;
  // We should have a picture.
  EXPECT_TRUE(!!picture_info.GetPicture());
  gfx::Rect picture_rect = gfx::ScaleToEnclosedRect(
      picture_info.GetPicture()->LayerRect(), min_scale);

  // The the picture should be large enough that scaling it never makes a rect
  // smaller than 1 px wide or tall.
  EXPECT_FALSE(picture_rect.IsEmpty()) << "Picture rect " <<
      picture_rect.ToString();
}

TEST(PicturePileTest, LargeInvalidateInflated) {
  FakeContentLayerClient client;
  FakeRenderingStatsInstrumentation stats_instrumentation;
  scoped_refptr<TestPicturePile> pile = new TestPicturePile;
  SkColor background_color = SK_ColorBLUE;

  float min_scale = 0.125;
  gfx::Size base_picture_size = pile->tiling().max_texture_size();

  gfx::Size layer_size = base_picture_size;
  pile->Resize(layer_size);
  pile->SetTileGridSize(gfx::Size(1000, 1000));
  pile->SetMinContentsScale(min_scale);

  // Update the whole layer.
  pile->Update(&client,
               background_color,
               false,
               gfx::Rect(layer_size),
               gfx::Rect(layer_size),
               1,
               &stats_instrumentation);

  // Invalidate something inside a tile.
  gfx::Rect invalidate_rect(50, 50, 100, 100);
  pile->Update(&client,
               background_color,
               false,
               invalidate_rect,
               gfx::Rect(layer_size),
               2,
               &stats_instrumentation);

  EXPECT_EQ(1, pile->tiling().num_tiles_x());
  EXPECT_EQ(1, pile->tiling().num_tiles_y());

  TestPicturePile::PictureInfo& picture_info =
      pile->picture_map().find(TestPicturePile::PictureMapKey(0, 0))->second;
  EXPECT_TRUE(!!picture_info.GetPicture());

  int expected_inflation = pile->buffer_pixels();

  Picture* base_picture = picture_info.GetPicture();
  gfx::Rect base_picture_rect(layer_size);
  base_picture_rect.Inset(-expected_inflation, -expected_inflation);
  EXPECT_EQ(base_picture_rect.ToString(),
            base_picture->LayerRect().ToString());
}

TEST(PicturePileTest, InvalidateOnTileBoundaryInflated) {
  FakeContentLayerClient client;
  FakeRenderingStatsInstrumentation stats_instrumentation;
  scoped_refptr<TestPicturePile> pile = new TestPicturePile;
  SkColor background_color = SK_ColorBLUE;

  float min_scale = 0.125;
  gfx::Size base_picture_size = pile->tiling().max_texture_size();

  gfx::Size layer_size =
      gfx::ToFlooredSize(gfx::ScaleSize(base_picture_size, 2.f));
  pile->Resize(layer_size);
  pile->SetTileGridSize(gfx::Size(1000, 1000));
  pile->SetMinContentsScale(min_scale);

  // Due to border pixels, we should have 3 tiles.
  EXPECT_EQ(3, pile->tiling().num_tiles_x());
  EXPECT_EQ(3, pile->tiling().num_tiles_y());

  // We should have 1/.125 - 1 = 7 border pixels.
  EXPECT_EQ(7, pile->buffer_pixels());
  EXPECT_EQ(7, pile->tiling().border_texels());

  // Update the whole layer to create initial pictures.
  pile->Update(&client,
               background_color,
               false,
               gfx::Rect(layer_size),
               gfx::Rect(layer_size),
               0,
               &stats_instrumentation);

  // Invalidate everything again to have a non zero invalidation
  // frequency.
  pile->Update(&client,
               background_color,
               false,
               gfx::Rect(layer_size),
               gfx::Rect(layer_size),
               1,
               &stats_instrumentation);

  // Invalidate something just over a tile boundary by a single pixel.
  // This will invalidate the tile (1, 1), as well as 1 row of pixels in (1, 0).
  gfx::Rect invalidate_rect(
      pile->tiling().TileBoundsWithBorder(0, 0).right(),
      pile->tiling().TileBoundsWithBorder(0, 0).bottom() - 1,
      50,
      50);
  pile->Update(&client,
               background_color,
               false,
               invalidate_rect,
               gfx::Rect(layer_size),
               2,
               &stats_instrumentation);

  for (int i = 0; i < pile->tiling().num_tiles_x(); ++i) {
    for (int j = 0; j < pile->tiling().num_tiles_y(); ++j) {
      TestPicturePile::PictureInfo& picture_info =
          pile->picture_map().find(
              TestPicturePile::PictureMapKey(i, j))->second;

      // Expect (1, 1) and (1, 0) to be invalidated once more
      // than the rest of the tiles.
      if (i == 1 && (j == 0 || j == 1)) {
        EXPECT_FLOAT_EQ(
            2.0f / TestPicturePile::PictureInfo::INVALIDATION_FRAMES_TRACKED,
            picture_info.GetInvalidationFrequencyForTesting());
      } else {
        EXPECT_FLOAT_EQ(
            1.0f / TestPicturePile::PictureInfo::INVALIDATION_FRAMES_TRACKED,
            picture_info.GetInvalidationFrequencyForTesting());
      }
    }
  }
}

TEST(PicturePileTest, StopRecordingOffscreenInvalidations) {
  FakeContentLayerClient client;
  FakeRenderingStatsInstrumentation stats_instrumentation;
  scoped_refptr<TestPicturePile> pile = new TestPicturePile;
  SkColor background_color = SK_ColorBLUE;

  float min_scale = 0.125;
  gfx::Size base_picture_size = pile->tiling().max_texture_size();

  gfx::Size layer_size =
      gfx::ToFlooredSize(gfx::ScaleSize(base_picture_size, 4.f));
  pile->Resize(layer_size);
  pile->SetTileGridSize(gfx::Size(1000, 1000));
  pile->SetMinContentsScale(min_scale);

  gfx::Rect viewport(0, 0, layer_size.width(), 1);

  // Update the whole layer until the invalidation frequency is high.
  int frame;
  for (frame = 0; frame < 33; ++frame) {
    pile->Update(&client,
                 background_color,
                 false,
                 gfx::Rect(layer_size),
                 viewport,
                 frame,
                 &stats_instrumentation);
  }

  // Make sure we have a high invalidation frequency.
  for (int i = 0; i < pile->tiling().num_tiles_x(); ++i) {
    for (int j = 0; j < pile->tiling().num_tiles_y(); ++j) {
      TestPicturePile::PictureInfo& picture_info =
          pile->picture_map().find(
              TestPicturePile::PictureMapKey(i, j))->second;
      EXPECT_FLOAT_EQ(1.0f, picture_info.GetInvalidationFrequencyForTesting())
          << "i " << i << " j " << j;
    }
  }

  // Update once more with a small viewport 0,0 layer_width by 1
  pile->Update(&client,
               background_color,
               false,
               gfx::Rect(layer_size),
               viewport,
               frame,
               &stats_instrumentation);

  for (int i = 0; i < pile->tiling().num_tiles_x(); ++i) {
    for (int j = 0; j < pile->tiling().num_tiles_y(); ++j) {
      TestPicturePile::PictureInfo& picture_info =
          pile->picture_map().find(
              TestPicturePile::PictureMapKey(i, j))->second;
      EXPECT_FLOAT_EQ(1.0f, picture_info.GetInvalidationFrequencyForTesting());

      // If the y far enough away we expect to find no picture (no re-recording
      // happened). For close y, the picture should change.
      if (j >= 2)
        EXPECT_FALSE(picture_info.GetPicture()) << "i " << i << " j " << j;
      else
        EXPECT_TRUE(picture_info.GetPicture()) << "i " << i << " j " << j;
    }
  }

  // Now update with no invalidation and full viewport
  pile->Update(&client,
               background_color,
               false,
               gfx::Rect(),
               gfx::Rect(layer_size),
               frame+1,
               &stats_instrumentation);

  for (int i = 0; i < pile->tiling().num_tiles_x(); ++i) {
    for (int j = 0; j < pile->tiling().num_tiles_y(); ++j) {
      TestPicturePile::PictureInfo& picture_info =
          pile->picture_map().find(
              TestPicturePile::PictureMapKey(i, j))->second;
      // Expect the invalidation frequency to be less than 1, since we just
      // updated with no invalidations.
      float expected_frequency =
          1.0f -
          1.0f / TestPicturePile::PictureInfo::INVALIDATION_FRAMES_TRACKED;

      EXPECT_FLOAT_EQ(expected_frequency,
                      picture_info.GetInvalidationFrequencyForTesting());

      // We expect that there are pictures everywhere now.
      EXPECT_TRUE(picture_info.GetPicture()) << "i " << i << " j " << j;
    }
  }
}

TEST_F(PicturePileTest, ClearingInvalidatesRecordedRect) {
  UpdateWholeLayer();

  gfx::Rect rect(0, 0, 5, 5);
  EXPECT_TRUE(pile_->CanRasterLayerRect(rect));
  EXPECT_TRUE(pile_->CanRasterSlowTileCheck(rect));

  pile_->Clear();

  // Make sure both the cache-aware check (using recorded region) and the normal
  // check are both false after clearing.
  EXPECT_FALSE(pile_->CanRasterLayerRect(rect));
  EXPECT_FALSE(pile_->CanRasterSlowTileCheck(rect));
}

TEST_F(PicturePileTest, FrequentInvalidationCanRaster) {
  // This test makes sure that if part of the page is frequently invalidated
  // and doesn't get re-recorded, then CanRaster is not true for any
  // tiles touching it, but is true for adjacent tiles, even if it
  // overlaps on borders (edge case).
  gfx::Size layer_size = gfx::ToFlooredSize(gfx::ScaleSize(pile_->size(), 4.f));
  pile_->Resize(layer_size);

  gfx::Rect tile01_borders = pile_->tiling().TileBoundsWithBorder(0, 1);
  gfx::Rect tile02_borders = pile_->tiling().TileBoundsWithBorder(0, 2);
  gfx::Rect tile01_noborders = pile_->tiling().TileBounds(0, 1);
  gfx::Rect tile02_noborders = pile_->tiling().TileBounds(0, 2);

  // Sanity check these two tiles are overlapping with borders, since this is
  // what the test is trying to repro.
  EXPECT_TRUE(tile01_borders.Intersects(tile02_borders));
  EXPECT_FALSE(tile01_noborders.Intersects(tile02_noborders));
  UpdateWholeLayer();
  EXPECT_TRUE(pile_->CanRasterLayerRect(tile01_noborders));
  EXPECT_TRUE(pile_->CanRasterSlowTileCheck(tile01_noborders));
  EXPECT_TRUE(pile_->CanRasterLayerRect(tile02_noborders));
  EXPECT_TRUE(pile_->CanRasterSlowTileCheck(tile02_noborders));
  // Sanity check that an initial paint goes down the fast path of having
  // a valid recorded viewport.
  EXPECT_TRUE(!pile_->recorded_viewport().IsEmpty());

  // Update the whole layer until the invalidation frequency is high.
  for (int frame = 0; frame < 33; ++frame) {
    UpdateWholeLayer();
  }

  // Update once more with a small viewport.
  gfx::Rect viewport(0, 0, layer_size.width(), 1);
  Update(layer_rect(), viewport);

  // Sanity check some pictures exist and others don't.
  EXPECT_TRUE(pile_->picture_map()
                  .find(TestPicturePile::PictureMapKey(0, 1))
                  ->second.GetPicture());
  EXPECT_FALSE(pile_->picture_map()
                   .find(TestPicturePile::PictureMapKey(0, 2))
                   ->second.GetPicture());

  EXPECT_TRUE(pile_->CanRasterLayerRect(tile01_noborders));
  EXPECT_TRUE(pile_->CanRasterSlowTileCheck(tile01_noborders));
  EXPECT_FALSE(pile_->CanRasterLayerRect(tile02_noborders));
  EXPECT_FALSE(pile_->CanRasterSlowTileCheck(tile02_noborders));
}

TEST_F(PicturePileTest, NoInvalidationValidViewport) {
  // This test validates that the recorded_viewport cache of full tiles
  // is still valid for some use cases.  If it's not, it's a performance
  // issue because CanRaster checks will go down the slow path.
  UpdateWholeLayer();
  EXPECT_TRUE(!pile_->recorded_viewport().IsEmpty());

  // No invalidation, same viewport.
  Update(gfx::Rect(), layer_rect());
  EXPECT_TRUE(!pile_->recorded_viewport().IsEmpty());

  // Partial invalidation, same viewport.
  Update(gfx::Rect(gfx::Rect(0, 0, 1, 1)), layer_rect());
  EXPECT_TRUE(!pile_->recorded_viewport().IsEmpty());

  // No invalidation, changing viewport.
  Update(gfx::Rect(), gfx::Rect(5, 5, 5, 5));
  EXPECT_TRUE(!pile_->recorded_viewport().IsEmpty());
}

}  // namespace
}  // namespace cc
